package org.alephium.flow.network.coordinator

import akka.actor.{Props, Terminated}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.Bootstrapper
import org.alephium.protocol.model.CliqueInfo
import org.alephium.serde._
import org.alephium.util.BaseActor

object CliqueCoordinator {
  def props()(implicit config: PlatformConfig): Props = Props(new CliqueCoordinator())

  sealed trait Event
  case object Ready extends Event {
    implicit val serde: Serde[Ready.type] = intSerde.xfmap[Ready.type](
      raw => if (raw == 0) Right(Ready) else Left(SerdeError.wrongFormat(s"Expecting 0 got $raw")),
      _   => 0)
  }
}

class CliqueCoordinator()(implicit val config: PlatformConfig)
    extends BaseActor
    with CliqueCoordinatorState {
  override def receive: Receive = awaitBrokers

  def awaitBrokers: Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connected to $remote")
      val connection = sender()
      val name       = BaseActor.envalidActorName(s"Broker-$remote")
      context.actorOf(BrokerConnector.props(connection), name)
      ()
    case info: BrokerConnector.BrokerInfo =>
      log.debug(s"Received broker info from ${info.address} id: ${info.id}")
      if (addBrokerInfo(info, sender())) {
        context watch sender()
      }
      if (isBrokerInfoFull) {
        log.debug(s"Broadcast clique info")
        context.parent ! Bootstrapper.ForwardConnection
        broadcast(buildCliqueInfo)
        context become awaitAck
      }
  }

  def awaitAck: Receive = {
    case BrokerConnector.Ack(id) =>
      log.debug(s"Broker $id is ready")
      if (0 <= id && id < config.brokerNum) {
        setReady(id)
        if (isAllReady) {
          log.debug("All the brokers are ready")
          broadcast(CliqueCoordinator.Ready)
          context become awaitTerminated(buildCliqueInfo)
        }
      }
  }

  def awaitTerminated(cliqueInfo: CliqueInfo): Receive = {
    case Terminated(actor) =>
      setClose(actor)
      if (isAllClosed) {
        log.debug("All the brokers are closed")
        context.parent ! cliqueInfo
        context stop self
      }
  }
}
