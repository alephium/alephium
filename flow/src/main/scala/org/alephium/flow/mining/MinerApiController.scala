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

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, ViewHandler}
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.flow.setting.{MiningSetting, NetworkSetting}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model.{BlockHash, ChainIndex, Nonce, Target}
import org.alephium.serde.{avectorSerde, serialize, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, AVector, BaseActor, Cache, Hex, TimeStamp}

object MinerApiController {
  def props(allHandlers: AllHandlers)(implicit
      brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      miningSetting: MiningSetting
  ): Props =
    Props(new MinerApiController(allHandlers)).withDispatcher(MiningDispatcher)

  sealed trait Command
  final case class Received(message: ClientMessage) extends Command

  def connection(remote: InetSocketAddress, connection: ActorRefT[Tcp.Command])(implicit
      groupConfig: GroupConfig,
      networkSetting: NetworkSetting
  ): Props = {
    Props(new MyConnectionHandler(remote, connection)).withDispatcher(MiningDispatcher)
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
}

class MinerApiController(allHandlers: AllHandlers)(implicit
    brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    miningSetting: MiningSetting
) extends BaseActor {
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

  var latestJobs: Option[AVector[(Job, BlockFlowTemplate)]]              = None
  val connections: ArrayBuffer[ActorRefT[ConnectionHandler.Command]]     = ArrayBuffer.empty
  val pendings: ArrayBuffer[(InetSocketAddress, ActorRefT[Tcp.Command])] = ArrayBuffer.empty
  val jobCache: Cache[ByteString, (BlockFlowTemplate, ByteString)] =
    Cache.fifo(brokerConfig.chainNum * miningSetting.jobCacheSizePerChain)

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
              ServerMessage.serialize(ServerMessage.from(Jobs(jobs.map(_._1))))
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
    case ViewHandler.NewTemplates(templates)                 => publishTemplates(templates)
    case ViewHandler.NewTemplate(template)                   => publishTemplate(template)
    case MinerApiController.Received(message: ClientMessage) => handleClientMessage(message)
    case BlockChainHandler.BlockAdded(hash) => handleSubmittedBlock(hash, succeeded = true)
    case BlockChainHandler.InvalidBlock(hash, reason) =>
      handleSubmittedBlock(hash, succeeded = false)
      log.error(s"Mined an invalid block ${hash.shortHex} due to: $reason")
  }

  def publishTemplates(templatess: IndexedSeq[IndexedSeq[BlockFlowTemplate]]): Unit = {
    val jobs = templatess.foldLeft(
      AVector.ofCapacity[(Job, BlockFlowTemplate)](templatess.length * brokerConfig.groups)
    ) { case (acc, templates) =>
      acc ++ AVector.from(templates.view.map(t => Job.fromWithoutTxs(t) -> t))
    }
    latestJobs = Some(jobs)
    publishLatestJobs()
  }

  def publishTemplate(template: BlockFlowTemplate): Unit = {
    val job = Job.fromWithoutTxs(template)
    val newJobs = latestJobs.map { existingJobs =>
      // TODO: test this!!!
      val jobIndex =
        template.index.from.value / brokerConfig.brokerNum * brokerConfig.groups + template.index.to.value
      existingJobs.replace(jobIndex, job -> template)
    }
    latestJobs = newJobs
    publishLatestJobs()
  }

  def publishLatestJobs(): Unit = {
    log.debug(
      s"Sending block templates to subscribers: #${connections.length} connections, #${pendings.length} pending connections"
    )
    latestJobs.foreach { jobs =>
      connections.foreach(
        _ ! ConnectionHandler.Send(
          ServerMessage.serialize(ServerMessage.from(Jobs(jobs.map(_._1))))
        )
      )

      jobs.foreach { case (job, template) =>
        val txsBlob = serialize(template.transactions)
        jobCache.put(MinerApiController.getCacheKey(job.headerBlob), template -> txsBlob)
      }
    }
  }

  def handleClientMessage(message: ClientMessage): Unit = message.payload match {
    case SubmitBlock(blockBlob) =>
      val header    = blockBlob.dropRight(1) // remove the encoding of empty txs
      val blockHash = PoW.hash(header)
      val headerKey = header.drop(Nonce.byteLength)
      jobCache.get(MinerApiController.getCacheKey(headerKey)) match {
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
            submit(blockHash, blockBytes, template.index)
            log.info(
              s"A new block ${blockHash.toHexString} got mined for ${template.index}, tx: ${template.transactions.length}, target: ${template.target}"
            )
          }
        case None =>
          sendSubmitResult(blockHash, succeeded = false, ActorRefT(sender()))
          log.error(
            s"The job for the block is expired: ${Hex.toHexString(blockBlob)}"
          )
      }
  }

  def submit(blockHash: BlockHash, blockBytes: ByteString, chainIndex: ChainIndex): Unit = {
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
