package org.alephium.flow.network

import akka.actor.{ActorRef, Props}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.BrokerHandler
import org.alephium.flow.network.coordinator.{Broker, CliqueCoordinator}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.BaseActor

object Bootstrapper {
  def props(builder: BrokerHandler.Builder,
            server: ActorRef,
            discoveryServer: ActorRef,
            cliqueManager: ActorRef)(implicit config: PlatformConfig): Props =
    Props(new Bootstrapper(builder, server, discoveryServer, cliqueManager))
}

class Bootstrapper(builder: BrokerHandler.Builder,
                   server: ActorRef,
                   discoveryServer: ActorRef,
                   cliqueManager: ActorRef)(implicit config: PlatformConfig)
    extends BaseActor {

  val sink = if (config.isMaster) {
    context.actorOf(CliqueCoordinator.props(), "SelfCliqueCoordinator")
  } else {
    context.actorOf(Broker.props(), "Broker")
  }

  override def receive: Receive = awaitCliqueInfo

  def awaitCliqueInfo: Receive = {
    case cliqueInfo: CliqueInfo =>
      val intraCliqueManager = context.system.actorOf(IntraCliqueManager.props(builder, cliqueInfo))
      cliqueManager ! CliqueManager.Start(cliqueInfo, intraCliqueManager)
      server ! cliqueManager
      discoveryServer ! cliqueInfo
      context stop self
    case c: Tcp.Connected =>
      sink.forward(c)
  }
}
