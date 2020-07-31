package org.alephium.flow.client

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.util.Timeout

import org.alephium.flow.Utils
import org.alephium.flow.core._
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.io.Storages
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformConfig
import org.alephium.util.{ActorRefT, BaseActor, EventBus, Service}

trait Node extends Service {
  implicit def config: PlatformConfig
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
    val timeout = Timeout(Utils.shutdownTimeout.asScala)
    monitor.ask(Node.Stop)(timeout).mapTo[Unit]
  }
}

object Node {
  def build(builders: BrokerHandler.Builder, storages: Storages)(
      implicit actorSystem: ActorSystem,
      platformConfig: PlatformConfig): Node = new Node {
    val config          = platformConfig
    implicit val system = actorSystem

    val blockFlow: BlockFlow = buildBlockFlowUnsafe(storages)

    val server: ActorRefT[TcpServer.Command] = ActorRefT
      .build[TcpServer.Command](system, TcpServer.props(config.publicAddress.getPort), "TcpServer")

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props(), "EventBus")

    val discoveryProps: Props =
      DiscoveryServer.props(config.publicAddress, config.bootstrap)(config, config)
    val discoveryServer: ActorRefT[DiscoveryServer.Command] =
      ActorRefT.build[DiscoveryServer.Command](system, discoveryProps, "DiscoveryServer")
    val cliqueManager: ActorRefT[CliqueManager.Command] =
      ActorRefT.build(system, CliqueManager.props(builders, discoveryServer), "CliqueManager")

    val allHandlers: AllHandlers = AllHandlers.build(system, cliqueManager, blockFlow, eventBus)

    cliqueManager ! CliqueManager.SendAllHandlers(allHandlers)

    val boostraper: ActorRefT[Bootstrapper.Command] =
      ActorRefT.build(system,
                      Bootstrapper.props(server, discoveryServer, cliqueManager),
                      "Bootstrapper")

    val monitor: ActorRefT[Node.Command] =
      ActorRefT.build(system, Monitor.props(this), "NodeMonitor")
  }

  def buildBlockFlowUnsafe(storages: Storages)(implicit config: PlatformConfig): BlockFlow = {
    val nodeStateStorage = storages.nodeStateStorage
    val isInitialized    = Utils.unsafe(nodeStateStorage.isInitialized())
    if (isInitialized) {
      BlockFlow.fromStorageUnsafe(storages)
    } else {
      val blockflow = BlockFlow.fromGenesisUnsafe(storages)
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
