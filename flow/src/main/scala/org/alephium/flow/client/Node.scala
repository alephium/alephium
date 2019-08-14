package org.alephium.flow.client

import akka.actor.ActorSystem
import org.alephium.flow.{PlatformConfig, PlatformEventBus}
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.network.{Bootstrapper, CliqueManager, DiscoveryServer, TcpServer}
import org.alephium.flow.storage._

case class Node(builders: BrokerHandler.Builder, name: String)(implicit config: PlatformConfig) {
  val system = ActorSystem(name, config.all)

  val blockFlow = BlockFlow.createUnsafe()

  val server = system.actorOf(TcpServer.props(config.publicAddress.getPort), "TcpServer")

  val eventBus = new PlatformEventBus()

  // TODO REMOVE
  val dummySpammer = system.actorOf(akka.actor.Props(new DummySpammer(eventBus)), "DummySpammer")

  val discoveryProps  = DiscoveryServer.props(config.bootstrap)(config)
  val discoveryServer = system.actorOf(discoveryProps, "DiscoveryServer")
  val cliqueManager =
    system.actorOf(CliqueManager.props(builders, discoveryServer), "CliqueManager")

  val allHandlers = AllHandlers.build(system, cliqueManager, blockFlow)
  cliqueManager ! allHandlers

  val boostraper =
    system.actorOf(Bootstrapper.props(server, discoveryServer, cliqueManager), "Bootstrapper")
}

// TODO Remove this! it used only for testing the event bus.
import akka.actor.{Actor, Timers}
class DummySpammer(eventBus: PlatformEventBus) extends Actor with Timers {
  import org.alephium.flow.PlatformEventBus.Event.Dummy
  import scala.concurrent.duration._
  timers.startPeriodicTimer("test", Dummy, 1.seconds)

  def receive: Receive = {
    case Dummy => eventBus.publish(Dummy)
  }
}
