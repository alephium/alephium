package org.alephium.flow.network.coordinator

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.BaseActor

object CliqueCoordinator {
  def props()(implicit config: PlatformConfig): Props = Props(new CliqueCoordinator())

  sealed trait Event
  case class CliqueReady(info: CliqueInfo) extends Event
}

class CliqueCoordinator()(implicit val config: PlatformConfig)
    extends BaseActor
    with CliqueCoordinatorState {
  override def receive: Receive = awaitBrokers

  def awaitBrokers: Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connected to $remote")
      val connection = sender()
      context.actorOf(BrokerConnector.props(connection), "CliqueCoordinator")
      ()
    case info: BrokerConnector.BrokerInfo =>
      log.debug(s"Received broker info from ${info.address} id: ${info.id}")
      if (addBrokerInfo(info, sender())) {
        context watch sender()
      }
      if (isBrokerInfoFull) {
        broadcast(buildCliqueInfo)
        context become awaitReady
      }
  }

  def awaitReady: Receive = {
    case BrokerConnector.Ack(id) =>
      log.debug(s"Broker $id is ready")
      if (0 <= id && id < config.brokerNum) {
        setReady(id)
        if (isAllReady) {
          context.parent ! buildCliqueInfo
          broadcast(BrokerConnector.Ready)
          context become awaitTerminated
        }
      }
  }

  def awaitTerminated: Receive = {
    case Terminated(actor) =>
      setClose(actor)
      if (isAllClosed) {
        context stop self
      }
  }
}
