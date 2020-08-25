package org.alephium.flow.client

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.util.Timeout

import org.alephium.flow.{FlowMonitor, Utils}
import org.alephium.flow.core._
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.io.Storages
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.broker.{BlockFlowSynchronizer, BrokerManager}
import org.alephium.flow.setting.AlephiumConfig
import org.alephium.util.{ActorRefT, BaseActor, EventBus, Service}

trait Node extends Service {
  implicit def config: AlephiumConfig
  def system: ActorSystem
  def blockFlow: BlockFlow
  def server: ActorRefT[TcpServer.Command]
  def discoveryServer: ActorRefT[DiscoveryServer.Command]
  def boostraper: ActorRefT[Bootstrapper.Command]
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

object Node {
  def build(storages: Storages)(
      implicit actorSystem: ActorSystem,
      _config: AlephiumConfig
  ): Node = new Node {
    implicit val system          = actorSystem
    val config                   = _config
    implicit val brokerConfig    = config.broker
    implicit val consensusConfig = config.consensus
    implicit val networkSetting  = config.network
    implicit val discoveryConfig = config.discovery

    val blockFlow: BlockFlow = buildBlockFlowUnsafe(storages)

    val brokerManager: ActorRefT[BrokerManager.Command] =
      ActorRefT.build(system, BrokerManager.props())

    val server: ActorRefT[TcpServer.Command] =
      ActorRefT
        .build[TcpServer.Command](
          system,
          TcpServer.props(config.network.publicAddress.getPort, brokerManager))

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props())

    val discoveryProps: Props =
      DiscoveryServer.props(config.network.publicAddress, config.discovery.bootstrap)
    val discoveryServer: ActorRefT[DiscoveryServer.Command] =
      ActorRefT.build[DiscoveryServer.Command](system, discoveryProps)

    val allHandlers: AllHandlers = AllHandlers.build(system, blockFlow, eventBus)

    val blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command] =
      ActorRefT.build(system, BlockFlowSynchronizer.props(blockFlow, allHandlers))
    val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(system,
                      CliqueManager.props(blockFlow,
                                          allHandlers,
                                          discoveryServer,
                                          brokerManager,
                                          blockFlowSynchronizer),
                      "CliqueManager")

    val boostraper: ActorRefT[Bootstrapper.Command] =
      ActorRefT.build(system,
                      Bootstrapper.props(server, discoveryServer, cliqueManager),
                      "Bootstrapper")

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
      node.server,
      node.discoveryServer,
      node.boostraper,
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
