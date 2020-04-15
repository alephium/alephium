package org.alephium.flow.client

import scala.concurrent.{Await, Future}

import akka.actor.{ActorSystem, Props, Terminated}

import org.alephium.flow.Utils
import org.alephium.flow.core._
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.platform.PlatformConfig
import org.alephium.util.{ActorRefT, BaseActor, EventBus}

trait Node {
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

  def shutdown(): Future[Unit] = {
    monitor ! Node.Stop
    system.whenTerminated.map(_ => ())(system.dispatcher)
  }
}

object Node {
  def build(builders: BrokerHandler.Builder, name: String)(
      implicit platformConfig: PlatformConfig): Node = new Node {
    val config              = platformConfig
    val system: ActorSystem = ActorSystem(name, config.all)

    val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe()

    val server: ActorRefT[TcpServer.Command] = ActorRefT
      .build[TcpServer.Command](system, TcpServer.props(config.publicAddress.getPort), "TcpServer")

    val eventBus: ActorRefT[EventBus.Message] =
      ActorRefT.build[EventBus.Message](system, EventBus.props(), "EventBus")

    val discoveryProps: Props = DiscoveryServer.props(config.bootstrap)(config)
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

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      Await.result(shutdown(), Utils.shutdownTimeout.asScala)
    }))
  }

  sealed trait Command
  case object Stop    extends Command
  case object TimeOut extends Command

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
        scheduleOnce(self, TimeOut, Utils.shutdownTimeout)
        terminate(orderedActors)
    }

    def terminate(actors: Seq[ActorRefT[_]]): Unit = {
      actors match {
        case Nil =>
          log.debug("Terminate the actor system")
          context.system.terminate()
          ()
        case toTerminate :: rest =>
          log.debug(s"Terminate ${toTerminate.ref.path.toStringWithoutAddress}")
          context watch toTerminate.ref
          context stop toTerminate.ref
          context become handle(toTerminate, rest)
      }
    }

    def handle(toTerminate: ActorRefT[_], rest: Seq[ActorRefT[_]]): Receive = {
      case Terminated(actor) if actor == toTerminate.ref =>
        terminate(rest)
      case TimeOut =>
        log.warning("Termination timeouts, let's shutdown immediately")
        context.system.terminate()
        ()
    }
  }
}
