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

import akka.actor.{Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import org.alephium.flow.network.broker.ConnectionHandler
import org.alephium.flow.setting.{AlephiumConfig, MiningSetting, NetworkSetting}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model.{Block, ChainIndex, NetworkType}
import org.alephium.serde.{serialize, SerdeResult, Staging}
import org.alephium.util.{ActorRefT, AVector}

object ExternalMinerMock {
  def singleNode(config: AlephiumConfig): Props = {
    require(config.broker.brokerNum == 1, "Only clique of 1 broker is supported")

    props(
      config,
      AVector(new InetSocketAddress(config.mining.apiInterface, config.network.minerApiPort))
    )
  }

  def props(config: AlephiumConfig, nodes: AVector[InetSocketAddress]): Props = {
    require(
      config.broker.groups % nodes.length == 0,
      s"Invalid number of nodes ${nodes.length} for groups ${config.broker.groups}"
    )

    props(
      config.network.networkType,
      nodes
    )(
      config.broker,
      config.network,
      config.mining
    )
  }

  def props(networkType: NetworkType, nodes: AVector[InetSocketAddress])(implicit
      groupConfig: GroupConfig,
      networkSetting: NetworkSetting,
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
    val miningConfig: MiningSetting
) extends Miner {
  private val apiConnections =
    Array.ofDim[Option[ActorRefT[ConnectionHandler.Command]]](nodes.length)
  private val groupNumPerBroker = brokerConfig.groups / nodes.length

  def receive: Receive = handleMining orElse handleMiningTasks orElse handleConnection

  def handleConnection: Receive = {
    case c: Tcp.Connected =>
      val remoteAddress = c.remoteAddress
      val addressIndex  = nodes.indexWhere(_ == remoteAddress)
      if (addressIndex != -1) {
        log.info(s"Connected to miner API: $remoteAddress")
        val connection = sender()
        val connectionHandler = ActorRefT[ConnectionHandler.Command](
          context.actorOf(ExternalMinerMock.connection(remoteAddress, ActorRefT(connection)))
        )
        context watch connectionHandler.ref
        apiConnections(addressIndex) = Some(connectionHandler)
      }
    case Tcp.CommandFailed(c: Tcp.Connect) =>
      log.error(s"Cannot connect to miner API ${c.remoteAddress}, stopping self ...")
      context.stop(self)
    case Terminated(_) =>
      log.info(
        s"Connection to miner API is closed, please check the nodes for more information. Shutdown the system now ..."
      )
      terminateSystem()
  }

  def subscribeForTasks(): Unit = {
    nodes.foreach { address =>
      IO(Tcp)(context.system) ! Tcp.Connect(address)
    }
  }

  def unsubscribeTasks(): Unit = {
    apiConnections.foreach(_.foreach(connection => context.stop(connection.ref)))
  }

  def publishNewBlock(block: Block): Unit = {
    val nodeIndex  = block.chainIndex.from.value / groupNumPerBroker
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
