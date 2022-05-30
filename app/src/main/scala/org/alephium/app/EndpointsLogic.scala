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

package org.alephium.app

import java.io.{StringWriter, Writer}
import java.net.InetAddress

import scala.concurrent._

import akka.pattern.ask
import akka.util.Timeout
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import sttp.model.{StatusCode, Uri}
import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.tapir.server.ServerEndpoint

import org.alephium.api.{ApiError, Endpoints, Try}
import org.alephium.api.model.{TransactionTemplate => _, _}
import org.alephium.app.FutureTry
import org.alephium.flow.client.Node
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{TxHandler, ViewHandler}
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.MiningBlob
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, InterCliqueManager}
import org.alephium.flow.network.bootstrap.IntraCliqueInfo
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.broker.MisbehaviorManager.Peers
import org.alephium.flow.setting.{ConsensusSetting, NetworkSetting}
import org.alephium.http.EndpointSender
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, CompilerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, LogConfig}
import org.alephium.serde._
import org.alephium.util._

// scalastyle:off method.length
trait EndpointsLogic extends Endpoints with EndpointSender with SttpClientInterpreter {
  def node: Node
  def miner: ActorRefT[Miner.Command]
  def blocksExporter: BlocksExporter

  private lazy val blockFlow: BlockFlow                        = node.blockFlow
  private lazy val txHandler: ActorRefT[TxHandler.Command]     = node.allHandlers.txHandler
  private lazy val viewHandler: ActorRefT[ViewHandler.Command] = node.allHandlers.viewHandler

  implicit def executionContext: ExecutionContext
  implicit def apiConfig: ApiConfig
  implicit def brokerConfig: BrokerConfig

  implicit lazy val groupConfig: GroupConfig         = brokerConfig
  implicit lazy val networkConfig: NetworkSetting    = node.config.network
  implicit lazy val consenseConfig: ConsensusSetting = node.config.consensus
  implicit lazy val compilerConfig: CompilerConfig   = node.config.compiler
  implicit lazy val logConfig: LogConfig             = node.config.node.logConfig
  implicit lazy val askTimeout: Timeout              = Timeout(apiConfig.askTimeout.asScala)

  private lazy val serverUtils: ServerUtils = new ServerUtils

  private var nodesOpt: Option[AVector[PeerAddress]] = None

  private def withSyncedClique[A](f: => FutureTry[A]): FutureTry[A] = {
    viewHandler.ref
      .ask(InterCliqueManager.IsSynced)
      .mapTo[InterCliqueManager.SyncedResult]
      .flatMap { result =>
        if (result.isSynced) {
          f
        } else {
          Future.successful(Left(ApiError.ServiceUnavailable("The clique is not synced")))
        }
      }
  }

  private def withMinerAddressSet[A](f: => FutureTry[A]): FutureTry[A] = {
    viewHandler
      .ask(ViewHandler.GetMinerAddresses)
      .mapTo[Option[AVector[LockupScript]]]
      .flatMap {
        case Some(_) => f
        case None =>
          Future.successful(Left(ApiError.InternalServerError("Miner addresses are not set up")))
      }
  }

  val getNodeInfoLogic = serverLogic(getNodeInfo) { _ =>
    Future.successful(
      Right(
        NodeInfo(
          NodeInfo.BuildInfo(BuildInfo.releaseVersion, BuildInfo.commitId),
          networkConfig.upnp.enabled,
          networkConfig.externalAddressInferred
        )
      )
    )
  }

  val getNodeVersionLogic = serverLogic(getNodeVersion) { _ =>
    Future.successful(
      Right(
        NodeVersion(
          ReleaseVersion.current
        )
      )
    )
  }

  val getChainParamsLogic = serverLogic(getChainParams) { _ =>
    fetchChainParams()
  }

  val getSelfCliqueLogic = serverLogic(getSelfClique) { _ =>
    fetchSelfClique()
  }

  val getInterCliquePeerInfoLogic = serverLogic(getInterCliquePeerInfo) { _ =>
    node.cliqueManager
      .ask(InterCliqueManager.GetSyncStatuses)
      .mapTo[Seq[InterCliqueManager.SyncStatus]]
      .map { syncedStatuses =>
        Right(AVector.from(syncedStatuses.map(EndpointsLogic.interCliquePeerInfoFrom)))
      }
  }

  val getDiscoveredNeighborsLogic = serverLogic(getDiscoveredNeighbors) { _ =>
    node.discoveryServer
      .ask(DiscoveryServer.GetNeighborPeers)
      .mapTo[DiscoveryServer.NeighborPeers]
      .map(response => Right(response.peers))
  }

  val getHistoryHashRateLogic = serverLogic(getHistoryHashRate) { timeInterval =>
    Future.successful(serverUtils.averageHashRate(blockFlow, timeInterval))
  }

  private val defaultHashRateDuration: Duration = Duration.ofMinutesUnsafe(10)
  val getCurrentHashRateLogic = serverLogic(getCurrentHashRate) { timeSpanOpt =>
    val timeSpan = timeSpanOpt.map(_.toDuration()).getOrElse(defaultHashRateDuration)
    val toTs     = TimeStamp.now()
    val fromTs   = toTs.minusUnsafe(timeSpan)
    val result   = serverUtils.averageHashRate(blockFlow, TimeInterval(fromTs, toTs))
    Future.successful(result)
  }

  val getBlockflowLogic = serverLogic(getBlockflow) { timeInterval =>
    Future.successful(serverUtils.getBlockflow(blockFlow, timeInterval))
  }

  val getBlockLogic = serverLogic(getBlock) { hash =>
    Future.successful(serverUtils.getBlock(blockFlow, GetBlock(hash)))
  }

  val isBlockInMainChainLogic = serverLogic(isBlockInMainChain) { hash =>
    Future.successful(serverUtils.isBlockInMainChain(blockFlow, hash))
  }

  val getBlockHeaderEntryLogic = serverLogic(getBlockHeaderEntry) { hash =>
    Future.successful(serverUtils.getBlockHeader(blockFlow, hash))
  }

  val getBalanceLogic = serverLogic(getBalance) { address =>
    Future.successful(serverUtils.getBalance(blockFlow, GetBalance(address)))
  }

  val getUTXOsLogic = serverLogic(getUTXOs) { address =>
    Future.successful(serverUtils.getUTXOsIncludePool(blockFlow, address))
  }

  val getGroupLogic = serverLogic(getGroup) {
    case address @ Address.Asset(_) =>
      Future.successful(serverUtils.getGroup(blockFlow, GetGroup(address)))
    case address @ Address.Contract(_) =>
      val failure: Future[Either[ApiError[_ <: StatusCode], Group]] =
        Future.successful(
          Left(ApiError.NotFound(s"Group not found. Please check another broker"))
            .withRight[Group]
        )
      brokerConfig.allGroups.take(brokerConfig.brokerNum).fold(failure) {
        case (prevResult, currentGroup: GroupIndex) =>
          prevResult flatMap {
            case Right(_) =>
              prevResult
            case _ =>
              requestFromGroupIndex(
                currentGroup,
                Future.successful(serverUtils.getGroup(blockFlow, GetGroup(address))),
                getGroupLocal,
                address
              )
          }
      }
  }

  val getGroupLocalLogic = serverLogic(getGroupLocal) { address =>
    Future.successful(serverUtils.getGroup(blockFlow, GetGroup(address)))
  }

  val getMisbehaviorsLogic = serverLogic(getMisbehaviors) { _ =>
    for {
      brokerPeers <- node.misbehaviorManager.ask(MisbehaviorManager.GetPeers).mapTo[Peers]
    } yield {
      Right(
        brokerPeers.peers.map { case MisbehaviorManager.Peer(addr, misbehavior) =>
          val status: PeerStatus = misbehavior match {
            case MisbehaviorManager.Penalty(value, _) => PeerStatus.Penalty(value)
            case MisbehaviorManager.Banned(until)     => PeerStatus.Banned(until)
          }
          PeerMisbehavior(addr, status)
        }
      )
    }
  }

  val misbehaviorActionLogic = serverLogic(misbehaviorAction) {
    case MisbehaviorAction.Ban(peers) =>
      node.misbehaviorManager ! MisbehaviorManager.Ban(peers)
      Future.successful(Right(()))
    case MisbehaviorAction.Unban(peers) =>
      node.misbehaviorManager ! MisbehaviorManager.Unban(peers)
      node.discoveryServer ! DiscoveryServer.Unban(peers)
      Future.successful(Right(()))
  }

  val getUnreachableBrokersLogic = serverLogic(getUnreachableBrokers) { _ =>
    node.discoveryServer
      .ask(DiscoveryServer.GetUnreachable)
      .mapTo[AVector[InetAddress]]
      .map(Right(_))
  }

  val discoveryActionLogic = serverLogic(discoveryAction) {
    case DiscoveryAction.Unreachable(peers) =>
      node.discoveryServer ! DiscoveryServer.UnreachablePeers(peers)
      Future.successful(Right(()))
    case DiscoveryAction.Reachable(peers) =>
      node.discoveryServer ! DiscoveryServer.Unban(peers)
      Future.successful(Right(()))
  }

  val getHashesAtHeightLogic = serverLogic(getHashesAtHeight) { case (chainIndex, height) =>
    Future.successful(
      serverUtils.getHashesAtHeight(
        blockFlow,
        chainIndex,
        GetHashesAtHeight(chainIndex.from.value, chainIndex.to.value, height)
      )
    )
  }

  val getChainInfoLogic = serverLogic(getChainInfo) { chainIndex =>
    Future.successful(serverUtils.getChainInfo(blockFlow, chainIndex))
  }

  val listUnconfirmedTransactionsLogic = serverLogic(listUnconfirmedTransactions) { _ =>
    Future.successful(serverUtils.listUnconfirmedTransactions(blockFlow))
  }

  type BaseServerEndpoint[A, B] = ServerEndpoint[A, ApiError[_ <: StatusCode], B, Any, Future]

  private def serverLogicRedirect[P, A](
      endpoint: BaseEndpoint[P, A]
  )(
      localLogic: P => Future[Either[ApiError[_ <: StatusCode], A]],
      getIndex: P => Either[ApiError[_ <: StatusCode], Option[GroupIndex]]
  ) = {
    serverLogic(endpoint) { params =>
      getIndex(params) match {
        case Right(Some(groupIndex)) =>
          requestFromGroupIndex(
            groupIndex,
            localLogic(params),
            endpoint,
            params
          )
        case Right(None) =>
          localLogic(params)
        case Left(e) =>
          Future.successful(Left[ApiError[_ <: StatusCode], A](e))
      }
    }
  }

  private def serverLogicRedirectWith[R, P, A](
      endpoint: BaseEndpoint[R, A]
  )(
      paramsConvert: R => Try[P],
      localLogic: P => Future[Either[ApiError[_ <: StatusCode], A]],
      getIndex: P => GroupIndex
  ) = {
    serverLogic(endpoint) { params =>
      paramsConvert(params) match {
        case Left(error) => Future.successful(Left(error))
        case Right(converted) =>
          requestFromGroupIndex(
            getIndex(converted),
            localLogic(converted),
            endpoint,
            params
          )
      }
    }
  }

  val buildMultisigAddressLogic = serverLogic(buildMultisigAddress) { buildMultisig =>
    Future.successful(
      serverUtils
        .buildMultisigAddress(
          buildMultisig.keys,
          buildMultisig.mrequired
        )
        .left
        .map(ApiError.BadRequest(_))
    )
  }

  val buildTransactionLogic = serverLogicRedirect(buildTransaction)(
    buildTransaction =>
      withSyncedClique {
        Future.successful(
          serverUtils
            .buildTransaction(
              blockFlow,
              buildTransaction
            )
        )
      },
    bt => Right(Some(LockupScript.p2pkh(bt.fromPublicKey).groupIndex(brokerConfig)))
  )

  val buildMultisigLogic = serverLogicRedirect(buildMultisig)(
    buildMultisig =>
      withSyncedClique {
        Future.successful(
          serverUtils
            .buildMultisig(
              blockFlow,
              buildMultisig
            )
        )
      },
    bt => Right(Some(bt.fromAddress.lockupScript.groupIndex(brokerConfig)))
  )

  val buildSweepAddressTransactionsLogic = serverLogicRedirect(buildSweepAddressTransactions)(
    buildSweepAddressTransactions =>
      withSyncedClique {
        Future.successful(
          serverUtils
            .buildSweepAddressTransactions(
              blockFlow,
              buildSweepAddressTransactions
            )
        )
      },
    bst => Right(Some(LockupScript.p2pkh(bst.fromPublicKey).groupIndex(brokerConfig)))
  )

  val submitTransactionLogic =
    serverLogicRedirectWith[SubmitTransaction, TransactionTemplate, TxResult](submitTransaction)(
      tx => serverUtils.createTxTemplate(tx),
      tx =>
        withSyncedClique {
          serverUtils.submitTransaction(txHandler, tx)
        },
      _.fromGroup
    )

  val submitMultisigTransactionLogic =
    serverLogicRedirectWith[SubmitMultisig, TransactionTemplate, TxResult](
      submitMultisigTransaction
    )(
      tx => serverUtils.createMultisigTxTemplate(tx),
      tx =>
        withSyncedClique {
          serverUtils.submitTransaction(txHandler, tx)
        },
      _.fromGroup
    )

  val getTransactionStatusLogic = serverLogicRedirect(getTransactionStatus)(
    { case (txId, fromGroup, toGroup) =>
      searchTransactionStatus(txId, fromGroup, toGroup)
    },
    { case (_, fromGroup, _) =>
      getGroupIndex(fromGroup)
    }
  )

  val getTransactionStatusLocalLogic = serverLogic(getTransactionStatusLocal) {
    case (txId, fromGroup, toGroup) =>
      searchTransactionStatus(txId, fromGroup, toGroup)
  }

  private def searchTransactionStatus(
      txId: Hash,
      chainFrom: Option[GroupIndex],
      chainTo: Option[GroupIndex]
  ): FutureTry[TxStatus] = {
    (chainFrom, chainTo) match {
      case (Some(from), Some(to)) =>
        Future.successful(
          serverUtils.getTransactionStatus(blockFlow, txId, ChainIndex(from, to))
        )
      case (Some(from), None) =>
        Future.successful(
          serverUtils.searchLocalTransactionStatus(
            blockFlow,
            txId,
            brokerConfig.chainIndexes.filter(_.from == from)
          )
        )
      case (None, Some(to)) =>
        Future.successful(
          serverUtils.searchLocalTransactionStatus(
            blockFlow,
            txId,
            brokerConfig.chainIndexes.filter(_.to == to)
          )
        )
      case (None, None) =>
        serverUtils.searchLocalTransactionStatus(blockFlow, txId, brokerConfig.chainIndexes) match {
          case Right(TxNotFound) =>
            searchTransactionStatusInOtherNodes(txId)
          case other => Future.successful(other)
        }
    }

  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def searchTransactionStatusInOtherNodes(txId: Hash): FutureTry[TxStatus] = {
    val otherGroupFrom = groupConfig.allGroups.filterNot(brokerConfig.contains)
    if (otherGroupFrom.isEmpty) {
      Future.successful(Right(TxNotFound))
    } else {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def rec(
          from: GroupIndex,
          remaining: AVector[GroupIndex]
      ): FutureTry[TxStatus] = {
        requestFromGroupIndex(
          from,
          Future.successful(Right(TxNotFound)),
          getTransactionStatus,
          (txId, Some(from), None)
        ).flatMap {
          case Right(TxNotFound) =>
            if (remaining.isEmpty) {
              Future.successful(Right(TxNotFound))
            } else {
              rec(remaining.head, remaining.tail)
            }
          case other => Future.successful(other)

        }
      }
      rec(otherGroupFrom.head, otherGroupFrom.tail)
    }
  }

  val decodeUnsignedTransactionLogic = serverLogic(decodeUnsignedTransaction) { tx =>
    Future.successful(
      serverUtils.decodeUnsignedTransaction(tx.unsignedTx).map { unsignedTx =>
        DecodeUnsignedTxResult(
          unsignedTx.fromGroup.value,
          unsignedTx.toGroup.value,
          UnsignedTx.fromProtocol(unsignedTx)
        )
      }
    )
  }

  val minerActionLogic = serverLogic(minerAction) { action =>
    withSyncedClique {
      withMinerAddressSet {
        action match {
          case MinerAction.StartMining => serverUtils.execute(miner ! Miner.Start)
          case MinerAction.StopMining  => serverUtils.execute(miner ! Miner.Stop)
        }
      }
    }
  }

  val minerListAddressesLogic = serverLogic(minerListAddresses) { _ =>
    viewHandler
      .ask(ViewHandler.GetMinerAddresses)
      .mapTo[Option[AVector[LockupScript.Asset]]]
      .map {
        case Some(lockupScripts) =>
          Right(
            MinerAddresses(
              lockupScripts.map(lockupScript => Address.Asset(lockupScript))
            )
          )
        case None => Left(ApiError.InternalServerError(s"Miner addresses are not set up"))
      }
  }

  val minerUpdateAddressesLogic = serverLogic(minerUpdateAddresses) { minerAddresses =>
    Future.successful {
      Miner
        .validateAddresses(minerAddresses.addresses)
        .map(_ => viewHandler ! ViewHandler.UpdateMinerAddresses(minerAddresses.addresses))
        .left
        .map(ApiError.BadRequest(_))
    }
  }

  val compileScriptLogic = serverLogic(compileScript) { query =>
    Future.successful(serverUtils.compileScript(query))
  }

  val buildExecuteScriptTxLogic = serverLogic(buildExecuteScriptTx) { query =>
    Future.successful(serverUtils.buildExecuteScriptTx(blockFlow, query))
  }

  val compileContractLogic = serverLogic(compileContract) { query =>
    Future.successful(serverUtils.compileContract(query))
  }

  val buildDeployContractTxLogic = serverLogic(buildDeployContractTx) { query =>
    Future.successful(serverUtils.buildDeployContractTx(blockFlow, query))
  }

  val verifySignatureLogic = serverLogic(verifySignature) { query =>
    Future.successful(serverUtils.verifySignature(query))
  }

  val checkHashIndexingLogic = serverLogic(checkHashIndexing) { _ =>
    Future.apply(blockFlow.checkHashIndexingUnsafe()) // Let's run it in the background
    Future.successful(Right(()))
  }

  val contractStateLogic = serverLogic(contractState) { case (contractAddress, groupIndex) =>
    requestFromGroupIndex(
      groupIndex,
      Future.successful(serverUtils.getContractState(blockFlow, contractAddress, groupIndex)),
      contractState,
      (contractAddress, groupIndex)
    )
  }

  val testContractLogic = serverLogic(testContract) { testContract: TestContract =>
    val blockFlow = BlockFlow.emptyUnsafe(node.config)
    Future.successful {
      for {
        completeTestContract <- testContract.toComplete()
        result               <- serverUtils.runTestContract(blockFlow, completeTestContract)
      } yield result
    }
  }

  val exportBlocksLogic = serverLogic(exportBlocks) { exportFile =>
    // Run the export in background
    Future.successful(
      blocksExporter
        .export(exportFile.filename)
        .left
        .map(error => logger.error(error.getMessage))
    )
    // Just validate the filename and return success
    Future.successful {
      blocksExporter
        .validateFilename(exportFile.filename)
        .map(_ => ())
        .left
        .map(error => ApiError.BadRequest(error.getMessage))
    }
  }

  val getContractEventsLogic = serverLogicRedirect(getContractEvents)(
    {
      case (contractAddress, counterRange, _) => {
        Future.successful {
          serverUtils.getEventsByContractId(
            blockFlow,
            counterRange.start,
            counterRange.endOpt,
            contractAddress.lockupScript.contractId
          )
        }
      }
    },
    {
      case (_, _, groupIndexOpt) => {
        getGroupIndex(groupIndexOpt)
      }
    }
  )

  val getContractEventsCurrentCountLogic = serverLogic(getContractEventsCurrentCount) {
    contractAddress =>
      Future.successful {
        serverUtils.getEventsForContractCurrentCount(blockFlow, contractAddress)
      }
  }

  val getEventsByTxIdLogic = serverLogicRedirect(getEventsByTxId)(
    { case (txId, _) =>
      Future.successful {
        serverUtils.getEventsByTxId(blockFlow, txId)
      }
    },
    {
      case (_, groupIndexOpt) => {
        getGroupIndex(groupIndexOpt)
      }
    }
  )

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  val metricsLogic = metrics.serverLogic[Future] { _ =>
    Future.successful {
      val writer: Writer = new StringWriter()
      try {
        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
        Right(writer.toString)
      } catch {
        case error: Throwable =>
          Left(ApiError.InternalServerError(error.getMessage))
      } finally {
        writer.close()
      }
    }
  }

  def fetchChainParams(): FutureTry[ChainParams] = {
    Future.successful(
      Right(
        ChainParams(
          networkConfig.networkId,
          consenseConfig.numZerosAtLeastInHash,
          brokerConfig.groupNumPerBroker,
          brokerConfig.groups
        )
      )
    )
  }

  def fetchSelfClique(): FutureTry[SelfClique] = {
    for {
      selfReady <- node.cliqueManager.ask(CliqueManager.IsSelfCliqueReady).mapTo[Boolean]
      synced <-
        if (selfReady) {
          viewHandler.ref
            .ask(InterCliqueManager.IsSynced)
            .mapTo[InterCliqueManager.SyncedResult]
            .map(_.isSynced)
        } else {
          Future.successful(false)
        }
      cliqueInfo <- node.bootstrapper.ask(Bootstrapper.GetIntraCliqueInfo).mapTo[IntraCliqueInfo]
    } yield {
      val selfClique = EndpointsLogic.selfCliqueFrom(
        cliqueInfo,
        selfReady = selfReady,
        synced = synced
      )
      if (selfReady) {
        nodesOpt = Some(selfClique.nodes)
      }
      Right(
        selfClique
      )
    }
  }

  private def requestFromGroupIndex[P, A](
      groupIndex: GroupIndex,
      f: => Future[Either[ApiError[_ <: StatusCode], A]],
      endpoint: BaseEndpoint[P, A],
      params: P
  ): Future[Either[ApiError[_ <: StatusCode], A]] =
    serverUtils.checkGroup(groupIndex) match {
      case Right(_) => f
      case Left(_) =>
        uriFromGroup(groupIndex).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(uri) =>
            send(endpoint, params, uri)
        }
    }

  private def uriFromGroup(
      fromGroup: GroupIndex
  ): Future[Either[ApiError[_ <: StatusCode], Uri]] =
    nodesOpt match {
      case Some(nodes) =>
        val peer = nodes(brokerConfig.brokerIndex(fromGroup))
        Future.successful(Right(Uri(peer.address.getHostAddress, peer.restPort)))
      case None =>
        fetchSelfClique().map { selfCliqueEither =>
          for {
            selfClique <- selfCliqueEither
          } yield {
            val peer = selfClique.peer(fromGroup)
            Uri(peer.address.getHostAddress, peer.restPort)
          }
        }
    }

  private def getGroupIndex(groupIndexOpt: Option[GroupIndex]) = {
    (brokerConfig.brokerNum, groupIndexOpt) match {
      case (1, _) =>
        Right(groupIndexOpt)
      case (_, Some(groupIndex)) =>
        Right(Some(groupIndex))
      case (_, None) =>
        Left(ApiError.BadRequest("`group` parameter is required with multiple brokers"))
    }
  }
}

object EndpointsLogic {
  def selfCliqueFrom(
      cliqueInfo: IntraCliqueInfo,
      selfReady: Boolean,
      synced: Boolean
  ): SelfClique = {
    SelfClique(
      cliqueInfo.id,
      cliqueInfo.peers.map(peer =>
        PeerAddress(peer.internalAddress.getAddress, peer.restPort, peer.wsPort, peer.minerApiPort)
      ),
      selfReady = selfReady,
      synced = synced
    )
  }

  def interCliquePeerInfoFrom(syncStatus: InterCliqueManager.SyncStatus): InterCliquePeerInfo = {
    val peerId = syncStatus.peerId
    InterCliquePeerInfo(
      peerId.cliqueId,
      peerId.brokerId,
      syncStatus.groupNumPerBroker,
      syncStatus.address,
      syncStatus.isSynced,
      syncStatus.clientInfo
    )
  }

  // Cannot do this in `BlockCandidate` as `flow.BlockTemplate` isn't accessible in `api`
  def blockTempateToCandidate(
      chainIndex: ChainIndex,
      template: MiningBlob
  ): BlockCandidate = {
    BlockCandidate(
      fromGroup = chainIndex.from.value,
      toGroup = chainIndex.to.value,
      headerBlob = template.headerBlob,
      target = template.target,
      txsBlob = template.txsBlob
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def blockSolutionToBlock(
      solution: BlockSolution
  ): Either[ApiError[_ <: StatusCode], (Block, U256)] = {
    deserialize[Block](solution.blockBlob) match {
      case Right(block) =>
        Right(block -> solution.miningCount)
      case Left(error) =>
        Left(ApiError.InternalServerError(s"Block deserialization error: ${error.getMessage}"))
    }
  }
}
