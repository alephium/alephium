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

package org.alephium.flow.client

import java.net.InetSocketAddress

import akka.actor.{Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.flow.setting.{MiningSetting, NetworkSetting}
import org.alephium.protocol.config.{BrokerConfig, EmissionConfig, GroupConfig}
import org.alephium.protocol.model.{Block, ChainIndex, NetworkType}
import org.alephium.serde.{serialize, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, AVector}

object ExternalMinerMock {
  def props(networkType: NetworkType, nodes: AVector[InetSocketAddress])(implicit
      groupConfig: GroupConfig,
      networkSetting: NetworkSetting,
      emissionConfig: EmissionConfig,
      miningConfig: MiningSetting
  ): Props = {
    // to pretend that there is only 1 node in the clique, so we could reuse MinerState
    implicit val brokerConfig: BrokerConfig = new BrokerConfig {
      override val brokerId: Int  = 0
      override val brokerNum: Int = 1
      override def groups: Int    = groupConfig.groups
    }
    Props(new ExternalMinerMock(networkType, nodes))
  }

  sealed trait Command
  final case class Received(message: ServerMessage) extends Command

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
      extends ConnectionHandler[ServerMessage] {
    override def tryDeserialize(data: ByteString): SerdeResult[Option[Staging[ServerMessage]]] = {
      ServerMessage.tryDeserialize(data)
    }

    override def handleNewMessage(message: ServerMessage): Unit = {
      context.parent ! Received(message)
    }
  }
}

// the addresses should be ordered by brokerId of the nodes
class ExternalMinerMock(val networkType: NetworkType, nodes: AVector[InetSocketAddress])(implicit
    val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting,
    val emissionConfig: EmissionConfig,
    val miningConfig: MiningSetting
) extends Miner {
  private val apiConnections =
    Array.ofDim[Option[ActorRefT[ConnectionHandler.Command]]](nodes.length)

  def receive: Receive = handleMining orElse handleMiningTasks orElse handleConnection

  def handleConnection: Receive = {
    case c: Tcp.Connected =>
      val remoteAddress = c.remoteAddress
      val addressIndex  = nodes.indexWhere(_ == remoteAddress)
      if (addressIndex != -1) {
        log.debug(s"Connected to master: $remoteAddress")
        val connection = sender()
        val connectionHandler = ActorRefT[ConnectionHandler.Command](
          context.actorOf(ExternalMinerMock.connection(remoteAddress, ActorRefT(connection)))
        )
        context watch connectionHandler.ref
        apiConnections(addressIndex) = Some(connectionHandler)
      }
    case Tcp.CommandFailed(c: Tcp.Connect) =>
      log.error(s"Cannot connect to ${c.remoteAddress}, stopping self ...")
      context.stop(self)
    case Terminated(connection) =>
      val index = apiConnections.indexWhere(_.exists(_.ref == connection))
      if (index != -1) {
        apiConnections(index) = None
      }
  }

  def subscribeForTasks(): Unit = {
    nodes.foreach { address =>
      IO(Tcp)(context.system) ! Tcp.Connect(address, pullMode = true)
    }
  }

  def unsubscribeTasks(): Unit = {
    apiConnections.foreach(_.foreach(connection => context.stop(connection.ref)))
  }

  def publishNewBlock(block: Block): Unit = {
    val nodeIndex  = block.chainIndex.from.value / brokerConfig.groupNumPerBroker
    val message    = SubmitBlock(serialize(block))
    val serialized = ClientMessage.serialize(message)
    apiConnections(nodeIndex).foreach(_ ! ConnectionHandler.Send(serialized))
  }

  def handleMiningTasks: Receive = { case ExternalMinerMock.Received(message) =>
    handleServerMessage(message)
  }

  def handleServerMessage(message: ServerMessage): Unit = message match {
    case Jobs(jobs) =>
      if (miningStarted) {
        updateAndStartTasks(jobs)
      }
    case m @ SubmitResult(fromGroup, toGroup, status) =>
      ChainIndex.from(fromGroup, toGroup) match {
        case Some(index) =>
          setIdle(index)
          if (!status) {
            log.error(s"Mined an invalid block for chain ($fromGroup, $toGroup)")
          }
        case None => log.error(s"Invalid group info in $m")
      }
  }

  def updateAndStartTasks(jobs: AVector[Job]): Unit = {
    jobs.foreach { job =>
      pendingTasks(job.fromGroup)(job.toGroup) = job.toMiningBlob
    }
    startNewTasks()
  }
}
