// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow.network.interclique

import scala.collection.mutable

import org.alephium.flow.Utils
import org.alephium.flow.handler.{AllHandlers, DependencyHandler, FlowHandler, TxHandler}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{CliqueManager, InterCliqueManager}
import org.alephium.flow.network.broker.{BrokerHandler => BaseBrokerHandler, MisbehaviorManager}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.ALPH
import org.alephium.protocol.message._
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AVector, Cache, Duration, TimeStamp}

trait BrokerHandler extends BaseBrokerHandler with SyncV2Handler {
  val maxBlockCapacity: Int              = brokerConfig.groupNumPerBroker * brokerConfig.groups * 10
  val maxTxsCapacity: Int                = maxBlockCapacity * 32
  val seenBlocks: Cache[BlockHash, Unit] = Cache.fifo[BlockHash, Unit](maxBlockCapacity)
  val seenTxExpiryDuration: Duration     = BrokerHandler.seenTxExpiryDuration
  val seenTxs: Cache[TransactionId, TimeStamp] =
    Cache.fifo[TransactionId, TimeStamp](maxTxsCapacity, identity[TimeStamp], seenTxExpiryDuration)

  def cliqueManager: ActorRefT[CliqueManager.Command]

  def allHandlers: AllHandlers

  override def handleHandshakeInfo(_remoteBrokerInfo: BrokerInfo, clientInfo: String): Unit = {
    remoteBrokerInfo = _remoteBrokerInfo
    val event = InterCliqueManager.HandShaked(
      ActorRefT[BaseBrokerHandler.Command](self),
      _remoteBrokerInfo,
      connectionType,
      clientInfo
    )
    publishEvent(event)
  }

  override def handleNewBlock(block: Block): Unit = {
    val blocks = AVector(block)
    if (validateFlowData(blocks, isBlock = true)) {
      seenBlocks.put(block.hash, ())
      val message = DependencyHandler.AddFlowData(blocks, dataOrigin)
      allHandlers.dependencyHandler ! message
    }
  }

  def exchangingV1: Receive = exchangingCommon orElse syncingV1 orElse flowEvents
  def exchangingV2: Receive = exchangingV1 orElse syncingV2

  def syncingV1: Receive = {
    case BaseBrokerHandler.SyncLocators(locators) =>
      val showLocators = Utils.showFlow(locators)
      log.debug(s"Send sync locators to $remoteAddress: $showLocators")
      send(InvRequest(locators))
    case BaseBrokerHandler.Received(InvRequest(requestId, locators)) =>
      if (validate(locators)) {
        log.debug(s"Received sync request from $remoteAddress: ${Utils.showFlow(locators)}")
        allHandlers.flowHandler ! FlowHandler.GetSyncInventories(
          requestId,
          locators,
          remoteBrokerInfo
        )
      } else {
        log.warning(s"Invalid locators from $remoteAddress: ${Utils.showFlow(locators)}")
      }
    case FlowHandler.SyncInventories(Some(requestId), inventories) =>
      log.debug(s"Send sync response to $remoteAddress: ${Utils.showFlow(inventories)}")
      if (inventories.sumBy(_.length) < brokerConfig.groups) {
        setRemoteSynced()
      }
      send(InvResponse(requestId, inventories))
    case BaseBrokerHandler.Received(InvResponse(_, hashes)) => handleInv(hashes)
    case BaseBrokerHandler.Received(NewBlockHash(hash))     => handleNewBlockHash(hash)
    case BaseBrokerHandler.RelayBlock(hash) =>
      if (seenBlocks.contains(hash)) {
        log.debug(s"Remote broker already have the block ${hash.shortHex}")
      } else {
        log.debug(s"Relay new block hash ${hash.shortHex} to $remoteAddress")
        seenBlocks.put(hash, ())
        send(NewBlockHash(hash))
      }
    case BaseBrokerHandler.RelayTxs(txs)                 => handleRelayTxs(txs)
    case BaseBrokerHandler.Received(NewTxHashes(hashes)) => handleNewTxHashes(hashes)
    case BaseBrokerHandler.DownloadTxs(txs) =>
      log.debug(s"Download txs ${Utils.showChainIndexedDigest(txs)} from $remoteAddress")
      send(TxsRequest(txs))
    case BaseBrokerHandler.Received(TxsRequest(id, txs)) =>
      handleTxsRequest(id, txs)
    case BaseBrokerHandler.Received(TxsResponse(id, txs)) =>
      handleTxsResponse(id, txs)
  }

  private def handleRelayTxs(txs: AVector[(ChainIndex, AVector[TransactionId])]): Unit = {
    val now = TimeStamp.now()
    val invs = txs.fold(AVector.empty[(ChainIndex, AVector[TransactionId])]) {
      case (acc, (chainIndex, txIds)) =>
        val selected = txIds.filter { txId =>
          val peerHaveTx = seenTxs.contains(txId)
          if (peerHaveTx) {
            log.debug(s"Remote broker already have the tx ${txId.shortHex}")
          } else {
            seenTxs.put(txId, now)
          }
          !peerHaveTx
        }
        if (selected.isEmpty) {
          acc
        } else {
          acc :+ ((chainIndex, selected))
        }
    }
    if (invs.nonEmpty) {
      send(NewTxHashes(invs))
    }
  }

  private def handleNewTxHashes(hashes: AVector[(ChainIndex, AVector[TransactionId])]): Unit = {
    log.debug(s"Received txs hashes ${Utils.showChainIndexedDigest(hashes)} from $remoteAddress")
    // ignore the tx announcements before synced
    if (selfSynced) {
      val now = TimeStamp.now()
      val result = hashes.mapE { case (chainIndex, txHashes) =>
        if (!brokerConfig.contains(chainIndex.from)) {
          Left(())
        } else {
          val invs = txHashes.filter { hash =>
            val duplicated = seenTxs.contains(hash)
            if (!duplicated) {
              seenTxs.put(hash, now)
            }
            !duplicated
          }
          Right((chainIndex, invs))
        }
      }
      result match {
        case Right(announcements) =>
          allHandlers.txHandler ! TxHandler.TxAnnouncements(announcements)
        case _ =>
          log.debug(s"Received invalid tx hashes from $remoteAddress")
          handleMisbehavior(MisbehaviorManager.InvalidGroup(remoteAddress))
      }
    }
  }

  private def handleTxsRequest(
      id: RequestId,
      txs: AVector[(ChainIndex, AVector[TransactionId])]
  ): Unit = {
    log.debug(
      s"Received txs request ${Utils.showChainIndexedDigest(txs)} from $remoteAddress with $id"
    )
    val result = txs.foldE(AVector.empty[TransactionTemplate]) {
      case (acc, (chainIndex, txHashes)) =>
        if (!brokerConfig.contains(chainIndex.from)) {
          Left(())
        } else {
          val txs = blockflow.getMemPool(chainIndex).getTxs(txHashes)
          Right(acc ++ txs)
        }
    }
    result match {
      case Right(txs) => send(TxsResponse(id, txs))
      case _ =>
        log.debug(s"Received invalid txs request from $remoteAddress")
        handleMisbehavior(MisbehaviorManager.InvalidGroup(remoteAddress))
    }
  }

  private def handleTxsResponse(id: RequestId, txs: AVector[TransactionTemplate]): Unit = {
    log.debug(
      s"Received #${txs.length} txs ${Utils.showDigest(txs.map(_.id))} from $remoteAddress with $id"
    )
    if (txs.nonEmpty) {
      if (txs.exists(tx => !brokerConfig.contains(tx.chainIndex.from))) {
        handleMisbehavior(MisbehaviorManager.InvalidGroup(remoteAddress))
      } else {
        allHandlers.txHandler ! TxHandler.AddToMemPool(
          txs,
          isIntraCliqueSyncing = false,
          isLocalTx = false
        )
      }
    }
  }

  private def handleNewBlockHash(hash: BlockHash): Unit = {
    if (validateBlockHash(hash)) {
      if (!seenBlocks.contains(hash)) {
        log.debug(s"Receive new block hash ${hash.shortHex} from $remoteAddress")
        seenBlocks.put(hash, ())
        blockFlowSynchronizer ! BlockFlowSynchronizer.BlockAnnouncement(hash)
      }
    } else {
      log.warning(s"Invalid new block hash ${hash.shortHex} from $remoteAddress")
    }
  }

  var selfSynced: Boolean   = false
  var remoteSynced: Boolean = false
  def setSelfSynced(): Unit = {
    if (!selfSynced) {
      log.info(s"Self synced with $remoteAddress")
      selfSynced = true
      cliqueManager ! CliqueManager.Synced(remoteBrokerInfo)
    }
  }
  def setRemoteSynced(): Unit = {
    if (!remoteSynced) {
      log.info(s"Remote $remoteAddress synced with our node")
      remoteSynced = true
    }
  }

  override def dataOrigin: DataOrigin = DataOrigin.InterClique(remoteBrokerInfo)

  @inline final protected def checkWork(hash: BlockHash): Boolean = {
    PoW.checkWork(hash, blockflow.consensusConfigs.maxAllowedMiningTarget)
  }

  def validateBlockHash(hash: BlockHash): Boolean = {
    if (!checkWork(hash)) {
      handleMisbehavior(MisbehaviorManager.InvalidPoW(remoteAddress))
      false
    } else {
      val ok = brokerConfig.contains(ChainIndex.from(hash).from)
      if (!ok) {
        handleMisbehavior(MisbehaviorManager.InvalidFlowChainIndex(remoteAddress))
      }
      ok
    }
  }

  def validate(locators: AVector[AVector[BlockHash]]): Boolean = {
    locators.forall(_.forall(validateBlockHash))
  }

  private def handleInv(hashes: AVector[AVector[BlockHash]]): Unit = {
    if (hashes.forall(_.isEmpty)) {
      setSelfSynced()
    } else {
      val showHashes = Utils.showFlow(hashes)
      if (validate(hashes)) {
        log.debug(s"Received inv response $showHashes from $remoteAddress")
        blockFlowSynchronizer ! BlockFlowSynchronizer.SyncInventories(hashes)
      } else {
        log.warning(s"Invalid inv response from $remoteAddress: $showHashes")
      }
    }
  }
}

object BrokerHandler {
  val seenTxExpiryDuration: Duration = Duration.ofMinutesUnsafe(5)

  def showChainState(tips: AVector[ChainTip]): String = {
    tips
      .map(t => s"(${t.hash.shortHex}, ${t.height}, ${t.weight.value})")
      .mkString("[", ",", "]")
  }

  def showIndexedHeights(heights: AVector[(ChainIndex, AVector[Int])]): String = {
    heights
      .map(p => s"${p._1} -> ${if (p._2.isEmpty) "[]" else s"[${p._2.head} .. ${p._2.last}]"}")
      .mkString(", ")
  }

  def showFlowData[T <: FlowData](data: AVector[AVector[T]]): String = {
    Utils.showFlow(data.map(_.map(_.hash)))
  }

  def showAncestors(heights: AVector[(ChainIndex, Int)]): String = {
    heights.map(p => s"${p._1} -> ${p._2}").mkString(", ")
  }
}

trait SyncV2Handler { _: BrokerHandler =>
  import SyncV2Handler._

  private[interclique] var states: Option[AVector[StatePerChain]] = None
  private[interclique] val pendingRequests = mutable.Map.empty[RequestId, RequestInfo]

  def syncingV2: Receive = {
    schedule(self, BaseBrokerHandler.CheckPendingRequest, RequestTimeout)

    val receive: Receive = {
      case BaseBrokerHandler.ChainState(tips) =>
        log.debug(s"Send chain state to $remoteAddress: ${BrokerHandler.showChainState(tips)}")
        send(ChainState(tips))

      case BaseBrokerHandler.Received(ChainState(tips)) =>
        if (checkChainState(tips)) {
          log.debug(
            s"Received chain state from $remoteAddress: ${BrokerHandler.showChainState(tips)}"
          )
          blockFlowSynchronizer ! BlockFlowSynchronizer.ChainState(tips)
        } else {
          log.warning(
            s"Invalid chain state ${BrokerHandler.showChainState(tips)} from $remoteAddress"
          )
          handleMisbehavior(MisbehaviorManager.InvalidChainState(remoteAddress))
        }

      case BaseBrokerHandler.GetAncestors(chains) =>
        handleGetAncestors(chains)
      case BaseBrokerHandler.Received(HeadersByHeightsRequest(id, heights)) =>
        handleHeadersRequest(id, heights)
      case BaseBrokerHandler.Received(HeadersByHeightsResponse(id, headerss)) =>
        handleHeadersResponse(id, headerss)
      case BaseBrokerHandler.CheckPendingRequest =>
        checkPendingRequest()
    }
    receive
  }

  private def checkChainState(tips: AVector[ChainTip]): Boolean = {
    val groupRange = brokerConfig.calIntersection(remoteBrokerInfo)
    tips.length == groupRange.length * brokerConfig.groups &&
    groupRange.indices.forall { index =>
      val fromGroup = groupRange(index)
      (0 until brokerConfig.groups).forall { toGroup =>
        val tip        = tips(index * brokerConfig.groups + toGroup)
        val chainIndex = tip.chainIndex
        (checkWork(tip.hash)
          && chainIndex.from.value == fromGroup && chainIndex.to.value == toGroup) ||
        (tip.height == ALPH.GenesisHeight && tip.weight == ALPH.GenesisWeight)
      }
    }
  }

  private def handleGetAncestors(chains: AVector[(ChainIndex, ChainTip, ChainTip)]): Unit = {
    assume(chains.nonEmpty)
    val states = mutable.ArrayBuffer.empty[StatePerChain]
    val heights = chains.map { case (chainIndex, bestTip, selfTip) =>
      val heightsPerChain = SyncV2Handler.calculateRequestSpan(bestTip.height, selfTip.height)
      states.addOne(StatePerChain(chainIndex, bestTip))
      (chainIndex, heightsPerChain)
    }
    this.states = Some(AVector.from(states))
    val request = HeadersByHeightsRequest(heights)
    log.debug(
      s"Sending HeadersByHeightsRequest to $remoteAddress: " +
        s"${BrokerHandler.showIndexedHeights(heights)}, id: ${request.id.value}"
    )
    sendRequest(request)
  }

  private def handleHeadersRequest(
      id: RequestId,
      heights: AVector[(ChainIndex, AVector[Int])]
  ): Unit = {
    if (heights.exists(c => !brokerConfig.contains(c._1.from))) {
      log.error(
        s"Received invalid HeadersByHeightsRequest: ${BrokerHandler.showIndexedHeights(heights)}"
      )
      stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
    } else {
      val results = mutable.ArrayBuffer.empty[AVector[BlockHeader]]
      heights.foreach { case (chainIndex, heightsPerChain) =>
        escapeIOError(
          blockflow.getHeaderChain(chainIndex).getHeadersByHeights(heightsPerChain),
          "Get flow data by heights"
        )(results.addOne)
      }
      send(HeadersByHeightsResponse(id, AVector.from(results)))
    }
  }

  private def handleHeadersResponse(
      id: RequestId,
      headerss: AVector[AVector[BlockHeader]]
  ): Unit = {
    pendingRequests.remove(id) match {
      case Some(info) =>
        info.payload match {
          case HeadersByHeightsRequest(_, chains) =>
            val isValid =
              headerss.length == chains.length &&
                headerss.forallWithIndex { case (headers, index) =>
                  val chainIndex = chains(index)._1
                  headers.nonEmpty && headers.forall(h =>
                    (checkWork(h.hash) && h.chainIndex == chainIndex) || h.isGenesis
                  )
                }
            if (isValid) {
              log.debug(
                s"Received valid HeadersByHeightsResponse: ${BrokerHandler.showFlowData(headerss)}"
              )
              handleAncestorResponse(headerss)
            } else {
              log.error(
                s"Received invalid HeadersByHeightsResponse: ${BrokerHandler.showFlowData(headerss)}"
              )
              stopOnError(MisbehaviorManager.InvalidFlowData(remoteAddress))
            }
          case _ => // this should never happen
            log.error(
              s"Internal error, expected a HeadersByHeightsRequest, but got ${info.payload}"
            )
            context.stop(self)
        }
      case None =>
        log.warning(s"Ignore unknown HeadersByHeightsResponse, request id: $id")
    }
  }

  private def handleAncestorResponse(headerss: AVector[AVector[BlockHeader]]): Unit = {
    val heights = headerss.foldE(AVector.empty[(ChainIndex, AVector[Int])]) { case (acc, headers) =>
      val chainIndex = headers.head.chainIndex
      getChainState(chainIndex) match {
        case Some(state) => handleAncestorResponse(state, headers, acc)
        case None        => Right(acc)
      }
    }
    heights match {
      case Right(heights) =>
        if (heights.nonEmpty) {
          val request = HeadersByHeightsRequest(heights)
          log.debug(
            s"Sending HeadersByHeightsRequest to $remoteAddress: " +
              s"${BrokerHandler.showIndexedHeights(heights)}, id: ${request.id.value}"
          )
          sendRequest(request)
        } else if (isAncestorFound) {
          states.foreach { states =>
            val ancestors =
              AVector.from(states).foldE(AVector.empty[(ChainIndex, Int)]) { case (acc, state) =>
                state.ancestor match {
                  case Some(header) =>
                    blockflow.getHeight(header).map(height => acc :+ (header.chainIndex -> height))
                  case None => Right(acc) // dead branch
                }
              }
            escapeIOError(ancestors, "Get ancestor height") { ancestors =>
              log.info(
                s"Found ancestors between self and the peer: ${BrokerHandler.showAncestors(ancestors)}"
              )
              blockFlowSynchronizer ! BlockFlowSynchronizer.Ancestors(ancestors)
            }
          }
          states = None
        }
      case Left(MisbehaviorT(misbehavior)) => stopOnError(misbehavior)
      case Left(IOErrorT(error)) => escapeIOError[Unit](Left(error), "Get ancestors")(_ => ())
    }
  }

  private def handleAncestorResponse(
      state: StatePerChain,
      headers: AVector[BlockHeader],
      requestsAcc: AVector[(ChainIndex, AVector[Int])]
  ): ResultT[AVector[(ChainIndex, AVector[Int])]] = {
    state.binarySearch match {
      case None =>
        from(headers.reverse.findE(blockflow.contains)).map {
          case Some(ancestor) =>
            log.debug(
              s"Found the ancestor between self and the peer $remoteAddress, chain index: ${state.chainIndex}, hash: ${ancestor.hash}"
            )
            state.setAncestor(ancestor)
            requestsAcc
          case None =>
            log.debug(
              s"Fallback to binary search to find the ancestor between self and the peer $remoteAddress"
            )
            requestsAcc :+ (state.chainIndex, AVector(state.startBinarySearch()))
        }
      case Some((start, end)) =>
        if (headers.length == 1) { // we have checked that the headers are not empty
          from(blockflow.contains(headers.head)).flatMap { exists =>
            val ancestor = if (exists) Some(headers.head) else None
            state.handleBinarySearch(start, end, ancestor) match {
              case Some(height) => Right(requestsAcc :+ (state.chainIndex, AVector(height)))
              case None =>
                if (!state.isAncestorFound) {
                  val chain = blockflow.getBlockChain(state.chainIndex)
                  escapeIOError(chain.getBlockHeader(chain.genesisHash), "Get genesis header") {
                    state.setAncestor
                  }
                }
                Right(requestsAcc)
            }
          }
        } else {
          Left(ErrorT(MisbehaviorManager.InvalidFlowData(remoteAddress)))
        }
    }
  }

  private def checkPendingRequest(): Unit = {
    val now = TimeStamp.now()
    pendingRequests.find(_._2.expiry < now) match {
      case Some((id, _)) =>
        log.error(s"The request ${id.value} sent to $remoteAddress timed out, stop the broker now")
        stopOnError(MisbehaviorManager.RequestTimeout(remoteAddress))
      case None => ()
    }
  }

  private def sendRequest(request: Payload.Solicited): Unit = {
    pendingRequests.addOne(request.id -> RequestInfo(request))
    send(request)
  }

  private def stopOnError(misbehavior: MisbehaviorManager.Misbehavior): Unit = {
    publishEvent(misbehavior)
    context.stop(self)
  }

  private def getChainState(chainIndex: ChainIndex): Option[StatePerChain] = {
    states.flatMap(_.find(s => s.chainIndex == chainIndex))
  }

  private def isAncestorFound: Boolean =
    states.isDefined && states.forall(_.forall(_.isAncestorFound))
}

object SyncV2Handler {
  sealed trait ErrorT
  final case class MisbehaviorT(value: MisbehaviorManager.Misbehavior) extends ErrorT
  final case class IOErrorT(value: IOError)                            extends ErrorT
  object ErrorT {
    def apply(value: MisbehaviorManager.Misbehavior): ErrorT = MisbehaviorT(value)
    def apply(value: IOError): ErrorT                        = IOErrorT(value)
  }

  type ResultT[T] = Either[ErrorT, T]

  def from[T](value: => IOResult[T]): ResultT[T] = value.left.map(ErrorT.apply)

  val RequestTimeout: Duration = Duration.ofMinutesUnsafe(1)
  final case class RequestInfo(payload: Payload, expiry: TimeStamp)
  object RequestInfo {
    def apply(payload: Payload.Solicited): RequestInfo =
      RequestInfo(payload, TimeStamp.now().plusUnsafe(RequestTimeout))
  }

  final class StatePerChain(
      val chainIndex: ChainIndex,
      val bestTip: ChainTip,
      var binarySearch: Option[(Int, Int)],
      var ancestor: Option[BlockHeader]
  ) {
    def startBinarySearch(): Int = {
      assume(binarySearch.isEmpty)
      binarySearch = Some((ALPH.GenesisHeight, bestTip.height))
      (ALPH.GenesisHeight + bestTip.height) / 2
    }

    def handleBinarySearch(
        start: Int,
        end: Int,
        ancestor: Option[BlockHeader]
    ): Option[Int] = {
      assume(binarySearch.isDefined)
      if (ancestor.isDefined) {
        this.ancestor = ancestor
      }
      val lastHeight = (start + end) / 2
      val (newStart, newEnd) = ancestor match {
        case Some(_) => (lastHeight, end)
        case None    => (start, lastHeight)
      }
      binarySearch = Some((newStart, newEnd))
      Option.when(newStart + 1 < newEnd)((newStart + newEnd) / 2)
    }

    @inline def setAncestor(blockHeader: BlockHeader): Unit = {
      assume(ancestor.isEmpty)
      ancestor = Some(blockHeader)
    }

    def isAncestorFound: Boolean = ancestor.isDefined
  }

  object StatePerChain {
    def apply(chainIndex: ChainIndex, bestTip: ChainTip): StatePerChain =
      new StatePerChain(chainIndex, bestTip, None, None)
  }

  @inline private def calcInRange(value: Int, min: Int, max: Int): Int = {
    if (value < min) min else if (value > max) max else value
  }

  def calculateRequestSpan(remoteHeight: Int, localHeight: Int): AVector[Int] = {
    val maxCount      = 12
    val requestHead   = math.max(remoteHeight - 1, ALPH.GenesisHeight)
    val requestBottom = math.max(localHeight - 1, ALPH.GenesisHeight)
    val totalSpan     = requestHead - requestBottom
    val span          = calcInRange(1 + totalSpan / maxCount, 2, 16)
    val count         = calcInRange(1 + totalSpan / span, 2, maxCount)
    val from          = math.max(requestHead - (count - 1) * span, ALPH.GenesisHeight)
    AVector.from(from.to(from + (count - 1) * span, span))
  }
}
