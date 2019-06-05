package org.alephium.flow.network.coordinator

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Terminated}
import akka.io.Tcp
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.{AVector, BaseActor}

object CliqueCoordinator {
  def props()(implicit config: PlatformConfig): Props = Props(new CliqueCoordinator())

  sealed trait Event
  case class CliqueReady(info: CliqueInfo) extends Event
}

class CliqueCoordinator()(implicit config: PlatformConfig) extends BaseActor {
  override def receive: Receive = awaitBrokers

  val brokerNum        = config.brokerNum
  val brokerAddresses  = Array.fill[Option[InetSocketAddress]](brokerNum)(None)
  val brokerConnectors = Array.fill[Option[ActorRef]](brokerNum)(None)
  val brokerCandidates = scala.collection.mutable.Set.empty[ActorRef]

  def awaitBrokers: Receive = {
    case Tcp.Connected(remote, _) =>
      log.debug(s"Connected to $remote")
      val connection = sender()
      val connector  = context.actorOf(BrokerConnector.props(connection), "CliqueCoordinator")
      brokerCandidates.add(connector)
      ()
    case BrokerConnector.BrokerInfo(id, address) =>
      if (brokerAddresses(id.value).isEmpty) {
        brokerAddresses(id.value)  = Some(address)
        brokerConnectors(id.value) = Some(sender())
        brokerCandidates.remove(sender())
        context watch sender()
      }
      if (!brokerAddresses.exists(_.isEmpty)) {
        val connectors = brokerConnectors.map(_.get)
        connectors.foreach(_ ! cliqueInfo)
        context become awaitReady
      }
  }

  private def cliqueInfo: CliqueInfo = {
    val addresses = AVector.from(brokerAddresses.map(_.get))
    CliqueInfo(CliqueId.generate, addresses, config.groupNumPerBroker)
  }

  val readys = Array.fill(brokerNum)(false)
  def awaitReady: Receive = {
    case BrokerConnector.Ack(id) =>
      readys(id) = true
      if (readys.forall(identity)) {
        context.parent ! cliqueInfo
        brokerConnectors.foreach(_.get ! BrokerConnector.Ready)
        context become awaitTerminated
      }
  }

  val closeds = Array.fill(brokerNum)(false)
  def awaitTerminated: Receive = {
    case Terminated(broker) =>
      val id = brokerConnectors.indexWhere(_.get == broker)
      if (id != -1) closeds(id) = true
      if (readys.forall(identity)) {
        context stop self
      }
  }
}
