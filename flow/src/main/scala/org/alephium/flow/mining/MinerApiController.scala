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

package org.alephium.flow.mining

import java.net.InetSocketAddress

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import akka.actor.{ActorRef, Cancellable, Props, Terminated}
import akka.io.{IO, Tcp}
import akka.pattern.pipe
import akka.util.ByteString

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, IOBaseActor, ViewHandler}
import org.alephium.flow.model.{AsyncUpdateState, BlockFlowTemplate}
import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.flow.setting.{MiningSetting, NetworkSetting}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model.{BlockHash, BlockHeader, ChainIndex, Nonce, Target}
import org.alephium.serde.{avectorSerde, serialize, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, AVector, Cache, Duration, Hex, TimeStamp}

object MinerApiController {
  def props(blockFlow: BlockFlow, allHandlers: AllHandlers)(implicit
      brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      miningSetting: MiningSetting
  ): Props = Props(new MinerApiController(blockFlow, allHandlers))

  sealed trait Command
  final case class Received(message: ClientMessage) extends Command
  final case class BuildJobsComplete(
      jobs: AVector[Job],
      cache: AVector[(ByteString, (BlockFlowTemplate, ByteString))],
      jobsMessage: ByteString
  ) extends Command
  final case class PublishJobs(jobsMessage: ByteString) extends Command

  def connection(remote: InetSocketAddress, connection: ActorRefT[Tcp.Command])(implicit
      groupConfig: GroupConfig,
      networkSetting: NetworkSetting
  ): Props = {
    Props(new MyConnectionHandler(remote, connection))
  }

  class MyConnectionHandler(
      val remoteAddress: InetSocketAddress,
      val connection: ActorRefT[Tcp.Command]
  )(implicit val groupConfig: GroupConfig, val networkSetting: NetworkSetting)
      extends ConnectionHandler[ClientMessage] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[ClientMessage]]] = {
      ClientMessage.tryDeserialize(data)
    }

    override def handleNewMessage(message: ClientMessage): Unit = {
      context.parent ! Received(message)
    }
  }

  private[mining] def getCacheKey(headerBlob: ByteString): ByteString = {
    val length = TimeStamp.byteLength + Target.byteLength
    assume(headerBlob.length > length)
    val targetBytes = headerBlob.takeRight(Target.byteLength)
    headerBlob.dropRight(length) ++ targetBytes
  }

  private[mining] def calcJobIndex(chainIndex: ChainIndex)(implicit config: BrokerConfig): Int = {
    chainIndex.from.value / config.brokerNum * config.groups + chainIndex.to.value
  }

  @inline private[mining] def calcPublishDelay(
      now: TimeStamp,
      lastPublishTs: TimeStamp
  )(implicit miningSetting: MiningSetting): Option[Duration] = {
    now -- lastPublishTs match {
      case Some(delta) =>
        if (delta < miningSetting.jobBroadcastDelay) {
          miningSetting.jobBroadcastDelay - delta
        } else {
          None
        }
      case None => Some(miningSetting.jobBroadcastDelay)
    }
  }

  final case class CachedTemplate(template: BlockFlowTemplate) {
    lazy val job: Job             = Job.fromWithoutTxs(template)
    lazy val txsBlob: ByteString  = serialize(template.transactions)
    lazy val cacheKey: ByteString = MinerApiController.getCacheKey(job.headerBlob)
  }
}

class MinerApiController(blockFlow: BlockFlow, allHandlers: AllHandlers)(implicit
    brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    miningSetting: MiningSetting
) extends IOBaseActor {
  import MinerApiController.CachedTemplate

  val apiAddress: InetSocketAddress =
    new InetSocketAddress(miningSetting.apiInterface, networkSetting.minerApiPort)
  IO(Tcp)(context.system) ! Tcp.Bind(self, apiAddress, options = Seq(Tcp.SO.ReuseAddress(true)))

  override def receive: Receive = {
    case Tcp.Bound(localAddress) =>
      log.info(s"Miner API server bound to $localAddress")
      context.become(handleAPI orElse ready)
    case Tcp.CommandFailed(_: Tcp.Bind) =>
      log.error(s"Binding failed")
      terminateSystem()
  }

  var templates: Option[AVector[CachedTemplate]]                         = None
  var latestJobs: Option[AVector[Job]]                                   = None
  val buildJobsState: AsyncUpdateState                                   = AsyncUpdateState()
  val connections: ArrayBuffer[ActorRefT[ConnectionHandler.Command]]     = ArrayBuffer.empty
  val pendings: ArrayBuffer[(InetSocketAddress, ActorRefT[Tcp.Command])] = ArrayBuffer.empty
  val jobCache: Cache[ByteString, (BlockFlowTemplate, ByteString)] =
    Cache.fifo(brokerConfig.chainNum * miningSetting.jobCacheSizePerChain)

  var lastPublishTimestamp: Option[TimeStamp] = None
  var publishJobsTask: Option[Cancellable]    = None

  def ready: Receive = {
    case Tcp.Connected(remote, _) =>
      allHandlers.viewHandler ! ViewHandler.Subscribe
      pendings.addOne(remote -> ActorRefT[Tcp.Command](sender()))

    case ViewHandler.SubscribeResult(succeeded) =>
      if (succeeded) {
        pendings.foreach { case (remote, connection) =>
          log.info(s"Remote $remote subscribed")
          val connectionHandler = ActorRefT[ConnectionHandler.Command](
            context.actorOf(MinerApiController.connection(remote, connection))
          )
          context.watch(connectionHandler.ref)
          connections.addOne(connectionHandler)
          latestJobs.foreach(jobs =>
            connectionHandler ! ConnectionHandler.Send(
              ServerMessage.serialize(ServerMessage.from(Jobs(jobs)))
            )
          )
        }
      } else {
        log.error(s"Failed in subscribing mining tasks. Closing all the connections")
        pendings.foreach(_._2 ! Tcp.Abort)
        connections.foreach(context stop _.ref)
      }
      pendings.clear()

    case Terminated(actor) =>
      log.info(s"The miner API connection to $actor is closed")
      removeConnection(actor)

    case Tcp.Aborted => ()
  }

  def removeConnection(actor: ActorRef): Unit = {
    connections.filterInPlace(_.ref != actor)
    if (connections.isEmpty) {
      allHandlers.viewHandler ! ViewHandler.Unsubscribe
    }
  }

  val submittingBlocks: mutable.HashMap[BlockHash, ActorRefT[ConnectionHandler.Command]] =
    mutable.HashMap.empty
  def handleAPI: Receive = {
    case ViewHandler.NewTemplates(templates) =>
      this.templates = Some(AVector.from(templates.view.flatten.map(CachedTemplate.apply)))
      buildJobsState.requestUpdate()
      tryBuildJobs()
    case ViewHandler.NewTemplate(template, lazyBroadcast) =>
      this.templates.foreach { templates =>
        val index = MinerApiController.calcJobIndex(template.index)
        this.templates = Some(templates.replace(index, CachedTemplate(template)))
        if (!lazyBroadcast) {
          buildJobsState.requestUpdate()
          tryBuildJobs()
        }
      }
    case MinerApiController.BuildJobsComplete(jobs, cache, jobsMessage) =>
      onBuildJobsComplete(jobs, cache, jobsMessage)
    case MinerApiController.PublishJobs(jobsMessage) =>
      publishJobsTask = None
      publishJobs(jobsMessage, TimeStamp.now())
    case MinerApiController.Received(message: ClientMessage) => handleClientMessage(message)
    case BlockChainHandler.BlockAdded(hash) => handleSubmittedBlock(hash, succeeded = true)
    case BlockChainHandler.InvalidBlock(hash, reason) =>
      handleSubmittedBlock(hash, succeeded = false)
      log.error(s"Mined an invalid block ${hash.shortHex} due to: $reason")
  }

  private def tryBuildJobs(): Unit = {
    if (buildJobsState.isUpdating) {
      log.debug("Skip building jobs due to pending updates")
    }
    if (buildJobsState.tryUpdate()) {
      this.templates.foreach { templates =>
        poolAsync {
          val jobs = templates.map(_.job)
          val cache =
            templates.map(template => (template.cacheKey, template.template -> template.txsBlob))
          val jobsMessage = ServerMessage.serialize(ServerMessage.from(Jobs(jobs)))
          MinerApiController.BuildJobsComplete(jobs, cache, jobsMessage)
        }.pipeTo(self)
      }
    }
  }

  private def onBuildJobsComplete(
      jobs: AVector[Job],
      cache: AVector[(ByteString, (BlockFlowTemplate, ByteString))],
      jobsMessage: ByteString
  ): Unit = {
    latestJobs = Some(jobs)
    publishJobs(jobsMessage)
    cache.foreach { case (key, value) => jobCache.put(key, value) }
    buildJobsState.setCompleted()
    tryBuildJobs()
  }

  private[mining] def publishJobs(jobsMessage: ByteString): Unit = {
    val now = TimeStamp.now()
    lastPublishTimestamp match {
      case Some(ts) =>
        MinerApiController.calcPublishDelay(now, ts) match {
          case Some(delay) =>
            publishJobsTask.foreach(_.cancel())
            publishJobsTask = Some(
              scheduleCancellableOnce(self, MinerApiController.PublishJobs(jobsMessage), delay)
            )
          case None => publishJobs(jobsMessage, now)
        }
      case None => publishJobs(jobsMessage, now)
    }
  }

  @inline private def publishJobs(jobsMessage: ByteString, timestamp: TimeStamp): Unit = {
    lastPublishTimestamp = Some(timestamp)
    log.debug(
      s"Sending block templates to subscribers: #${connections.length} connections, #${pendings.length} pending connections"
    )
    connections.foreach(_ ! ConnectionHandler.Send(jobsMessage))
  }

  def handleClientMessage(message: ClientMessage): Unit = message.payload match {
    case SubmitBlock(blockBlob) =>
      val header    = blockBlob.dropRight(1) // remove the encoding of empty txs
      val blockHash = PoW.hash(header)
      val headerKey = header.drop(Nonce.byteLength)
      val cacheKey  = MinerApiController.getCacheKey(headerKey)
      jobCache.get(cacheKey) match {
        case Some((template, txBlob)) =>
          val blockBytes = header ++ txBlob
          if (ChainIndex.from(blockHash) != template.index) {
            handleSubmissionFailure(
              blockHash,
              s"The mined block has invalid chainindex: ${blockHash.toHexString}, expected chainindex: ${template.index}"
            )
          } else if (!PoW.checkWork(blockHash, template.target)) {
            handleSubmissionFailure(
              blockHash,
              s"The mined block has invalid work: ${blockHash.toHexString}"
            )
          } else {
            trySubmit(template, blockBytes, blockHash)
            jobCache.remove(cacheKey)
            ()
          }
        case None =>
          handleSubmissionFailure(
            blockHash,
            s"The job for the block is expired or duplicated: ${Hex.toHexString(blockBlob)}"
          )
      }
  }

  private def trySubmit(
      template: BlockFlowTemplate,
      blockBytes: ByteString,
      blockHash: BlockHash
  ): Unit = {
    // use dummy header to avoid deserialization
    val header = template.dummyHeader()
    escapeIOError(isDuplicate(header, template.index)) { duplicate =>
      if (!duplicate) {
        submit(blockHash, blockBytes, template.index)
        log.info(
          s"A new block ${blockHash.toHexString} got mined for ${template.index}, tx: ${template.transactions.length}, target: ${template.target}"
        )
      } else {
        handleSubmissionFailure(
          blockHash,
          s"Ignore block ${blockHash.toHexString} because another block from the same mining job is already mined, there will be no mining rewards"
        )
      }
    }
  }

  private def isDuplicate(header: BlockHeader, chainIndex: ChainIndex) = {
    val parentHash = header.uncleHash(chainIndex.to)
    for {
      parentHeight <- blockFlow.getHeight(parentHash)
      hashes       <- blockFlow.getHashes(chainIndex, parentHeight + 1)
      headers      <- hashes.mapE(blockFlow.getBlockHeader)
    } yield headers.exists(BlockHeader.fromSameTemplate(_, header))
  }

  private def submit(blockHash: BlockHash, blockBytes: ByteString, chainIndex: ChainIndex): Unit = {
    allHandlers.getBlockHandler(chainIndex) match {
      case Some(blockHandler) =>
        val handlerMessage =
          BlockChainHandler.ValidateMinedBlock(blockHash, blockBytes, ActorRefT(self))
        blockHandler ! handlerMessage
        submittingBlocks.addOne(blockHash -> ActorRefT(sender()))
      case None =>
        log.error(s"Block with index ${chainIndex} does not belong to ${brokerConfig}")
    }
  }

  def handleSubmittedBlock(hash: BlockHash, succeeded: Boolean): Unit = {
    submittingBlocks.remove(hash).foreach(sendSubmitResult(hash, succeeded, _))
  }

  private def sendSubmitResult(
      hash: BlockHash,
      succeeded: Boolean,
      client: ActorRefT[ConnectionHandler.Command]
  ): Unit = {
    val chainIndex = ChainIndex.from(hash)
    val payload    = SubmitResult(chainIndex.from.value, chainIndex.to.value, hash, succeeded)
    val message    = ServerMessage.from(payload)
    client ! ConnectionHandler.Send(ServerMessage.serialize(message))
  }

  private def handleSubmissionFailure(blockHash: BlockHash, message: String): Unit = {
    sendSubmitResult(blockHash, succeeded = false, ActorRefT(sender()))
    log.error(message)
  }
}
