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
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.flow.setting.{MiningSetting, NetworkSetting}
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.serde.{deserialize, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, AVector, BaseActor, Hex}

object MinerApiController {
  def props(allHandlers: AllHandlers)(implicit
      brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      miningSetting: MiningSetting
  ): Props =
    Props(new MinerApiController(allHandlers))

  sealed trait Command
  final case class Received(message: ClientMessage) extends Command

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

  val connections: ArrayBuffer[ActorRefT[ConnectionHandler.Command]]     = ArrayBuffer.empty
  val pendings: ArrayBuffer[(InetSocketAddress, ActorRefT[Tcp.Command])] = ArrayBuffer.empty

  def ready: Receive = {
    case Tcp.Connected(remote, _) =>
      allHandlers.viewHandler ! ViewHandler.Subscribe
      pendings.addOne(remote -> ActorRefT[Tcp.Command](sender()))

    case ViewHandler.SubscribeResult(succeeded) =>
      if (succeeded) {
        pendings.foreach { case (remote, connection) =>
          val connectionHandler = ActorRefT[ConnectionHandler.Command](
            context.actorOf(MinerApiController.connection(remote, connection))
          )
          context.watch(connectionHandler.ref)
          connections.addOne(connectionHandler)
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
    case MinerApiController.Received(message: ClientMessage) => handleClientMessage(message)
    case BlockChainHandler.BlockAdded(hash)                  => handleSubmittedBlock(hash, succeeded = true)
    case BlockChainHandler.InvalidBlock(hash) =>
      handleSubmittedBlock(hash, succeeded = false)
      log.error(s"Mined an invalid block ${hash.shortHex}")
  }

  def publishTemplates(templatess: IndexedSeq[IndexedSeq[BlockFlowTemplate]]): Unit = {
    val jobs = templatess.foldLeft(AVector.ofSize[Job](templatess.length * brokerConfig.groups)) {
      case (acc, templates) =>
        acc ++ AVector.from(templates.view.map(Job.from))
    }
    connections.foreach(_ ! ConnectionHandler.Send(ServerMessage.serialize(Jobs(jobs))))
  }

  def handleClientMessage(message: ClientMessage): Unit = message match {
    case SubmitBlock(blockBlob) =>
      deserialize[Block](blockBlob) match {
        case Right(block) => submit(block)
        case Left(error) =>
          log.error(
            s"Deserialization error for submited block: $error : ${Hex.toHexString(blockBlob)}"
          )
      }
  }

  def submit(block: Block): Unit = {
    allHandlers.getBlockHandler(block.chainIndex) match {
      case Some(blockHandler) =>
        val handlerMessage = BlockChainHandler.Validate(block, ActorRefT(self), Local)
        blockHandler ! handlerMessage
        submittingBlocks.addOne(block.hash -> ActorRefT(sender()))
      case None =>
        log.error(s"Block with index ${block.chainIndex} does not belong to ${brokerConfig}")
    }
  }

  def handleSubmittedBlock(hash: BlockHash, succeeded: Boolean): Unit = {
    submittingBlocks.get(hash).foreach { client =>
      val chainIndex = ChainIndex.from(hash)
      val message    = SubmitResult(chainIndex.from.value, chainIndex.to.value, succeeded)
      client ! ConnectionHandler.Send(ServerMessage.serialize(message))
      submittingBlocks.remove(hash)
    }
  }
}
