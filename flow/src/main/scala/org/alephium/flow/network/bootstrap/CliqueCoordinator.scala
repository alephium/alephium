package org.alephium.flow.network.bootstrap

import akka.actor.{Props, Terminated}
import akka.io.Tcp

import org.alephium.flow.network.Bootstrapper
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig}
import org.alephium.serde._
import org.alephium.util.{ActorRefT, BaseActor}

object CliqueCoordinator {
  def props(bootstrapper: ActorRefT[Bootstrapper.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      discoveryConfig: DiscoveryConfig): Props =
    Props(new CliqueCoordinator(bootstrapper))

  sealed trait Event
  case object Ready extends Event {
    implicit val serde: Serde[Ready.type] = intSerde.xfmap[Ready.type](
      raw => if (raw == 0) Right(Ready) else Left(SerdeError.wrongFormat(s"Expecting 0 got $raw")),
      _   => 0)
  }
}

class CliqueCoordinator(bootstrapper: ActorRefT[Bootstrapper.Command])(
    implicit val brokerConfig: BrokerConfig,
    val networkSetting: NetworkSetting,
    val discoveryConfig: DiscoveryConfig)
    extends BaseActor
    with CliqueCoordinatorState {
  override def receive: Receive = awaitBrokers

  def awaitBrokers: Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connected to $remote")
      val connection = sender()
      val name       = BaseActor.envalidActorName(s"Broker-$remote")
      context.actorOf(BrokerConnector.props(remote, connection, self), name)
      ()
    case info: PeerInfo =>
      log.debug(s"Received broker info from ${info.publicAddress} id: ${info.id}")
      if (addBrokerInfo(info, sender())) {
        context watch sender()
      }
      if (isBrokerInfoFull) {
        log.debug(s"Broadcast clique info")
        bootstrapper ! Bootstrapper.ForwardConnection
        val cliqueInfo = buildCliqueInfo()
        broadcast(BrokerConnector.Send(cliqueInfo))
        context become awaitAck(cliqueInfo)
      }
  }

  def awaitAck(cliqueInfo: IntraCliqueInfo): Receive = {
    case Message.Ack(id) =>
      log.debug(s"Broker $id is ready")
      if (0 <= id && id < brokerConfig.brokerNum) {
        setReady(id)
        if (isAllReady) {
          log.debug("All the brokers are ready")
          broadcast(CliqueCoordinator.Ready)
          context become awaitTerminated(cliqueInfo)
        }
      }
  }

  def awaitTerminated(cliqueInfo: IntraCliqueInfo): Receive = {
    case Terminated(actor) =>
      setClose(actor)
      if (isAllClosed) {
        log.debug("All the brokers are closed")
        bootstrapper ! Bootstrapper.SendIntraCliqueInfo(cliqueInfo)
        context stop self
      }
  }
}
