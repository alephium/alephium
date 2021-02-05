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

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.{FlowMonitor, Utils}
import org.alephium.flow.core._
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.io.Storages
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpController}
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.protocol.ALF
import org.alephium.util.{ActorRefT, BaseActor, EventBus, Service}

trait Node extends Service {
  implicit def config: AlephiumConfig
  def system: ActorSystem
  def blockFlow: BlockFlow
  def misbehaviorManager: ActorRefT[MisbehaviorManager.Command]
  def tcpController: ActorRefT[TcpController.Command]
  def discoveryServer: ActorRefT[DiscoveryServer.Command]
  def bootstrapper: ActorRefT[Bootstrapper.Command]
  def cliqueManager: ActorRefT[CliqueManager.Command]
  def eventBus: ActorRefT[EventBus.Message]
  def allHandlers: AllHandlers
  def monitor: ActorRefT[Node.Command]

  override implicit def executionContext: ExecutionContext = system.dispatcher

  override def subServices: ArraySeq[Service] = ArraySeq.empty

  override protected def startSelfOnce(): Future[Unit] = Future.successful(())

  override protected def stopSelfOnce(): Future[Unit] = {
    val timeout = Timeout(FlowMonitor.shutdownTimeout.asScala)
    monitor.ask(Node.Stop)(timeout).mapTo[Unit]
  }
}

// scalastyle:off method.length
object Node {
  def build(storages: Storages)(
      implicit actorSystem: ActorSystem,
      _config: AlephiumConfig
  ): Node = new Node with StrictLogging {
    implicit val system          = actorSystem
    val config                   = _config
    implicit val brokerConfig    = config.broker
    implicit val consensusConfig = config.consensus
    implicit val networkSetting  = config.network
    implicit val discoveryConfig = config.discovery

    val blockFlow: BlockFlow = buildBlockFlowUnsafe(storages)

    val misbehaviorManager: ActorRefT[MisbehaviorManager.Command] =
      ActorRefT.build(system, MisbehaviorManager.props(ALF.BanDuration))

    val tcpController: ActorRefT[TcpController.Command] =
      ActorRefT
        .build[TcpController.Command](
          system,
          TcpController.props(config.network.bindAddress, misbehaviorManager))

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props())

    val discoveryProps: Props =
      DiscoveryServer.props(networkSetting.bindAddress, config.discovery.bootstrap)
    val discoveryServer: ActorRefT[DiscoveryServer.Command] =
      ActorRefT.build[DiscoveryServer.Command](system, discoveryProps)

    val allHandlers: AllHandlers = AllHandlers.build(system, blockFlow, eventBus)

    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command] =
      ActorRefT.build(system, BlockFlowSynchronizer.props(blockFlow, allHandlers))
    val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(
        system,
        CliqueManager.props(blockFlow, allHandlers, discoveryServer, blockFlowSynchronizer),
        "CliqueManager")

    val bootstrapper: ActorRefT[Bootstrapper.Command] =
      ActorRefT.build(system, Bootstrapper.props(tcpController, cliqueManager), "Bootstrapper")

    val monitor: ActorRefT[Node.Command] =
      ActorRefT.build(system, Monitor.props(this), "NodeMonitor")
  }

  def buildBlockFlowUnsafe(storages: Storages)(implicit config: AlephiumConfig): BlockFlow = {
    val nodeStateStorage = storages.nodeStateStorage
    val isInitialized    = Utils.unsafe(nodeStateStorage.isInitialized())
    if (isInitialized) {
      BlockFlow.fromStorageUnsafe(storages, config.genesisBlocks)(config.broker,
                                                                  config.consensus,
                                                                  config.mempool)
    } else {
      val blockflow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)(config.broker,
                                                                                  config.consensus,
                                                                                  config.mempool)
      Utils.unsafe(nodeStateStorage.setInitialized())
      blockflow
    }
  }

  sealed trait Command
  case object Stop extends Command

  object Monitor {
    def props(node: Node): Props = Props(new Monitor(node))
  }

  class Monitor(node: Node) extends BaseActor {
    private val orderedActors = Seq(
      node.misbehaviorManager,
      node.tcpController,
      node.discoveryServer,
      node.bootstrapper,
      node.cliqueManager,
      node.eventBus
    ) ++ node.allHandlers.orderedHandlers

    override def receive: Receive = {
      case Stop =>
        log.info("Stopping the node")
        terminate(orderedActors, sender())
    }

    def terminate(actors: Seq[ActorRefT[_]], answerTo: ActorRef): Unit = {
      actors match {
        case Nil =>
          log.debug("All actors terminated")
          answerTo ! ()
        case toTerminate :: rest =>
          log.debug(s"Terminate ${toTerminate.ref.path.toStringWithoutAddress}")
          context watch toTerminate.ref
          context stop toTerminate.ref
          context become handle(toTerminate, rest, answerTo)
      }
    }

    def handle(toTerminate: ActorRefT[_], rest: Seq[ActorRefT[_]], answerTo: ActorRef): Receive = {
      case Terminated(actor) if actor == toTerminate.ref =>
        terminate(rest, answerTo)
    }
  }
}
