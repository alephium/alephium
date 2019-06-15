package org.alephium.flow.network.coordinator

import java.net.InetSocketAddress

import akka.actor.ActorRef
import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{CliqueId, CliqueInfo}
import org.alephium.util.AVector

trait CliqueCoordinatorState {
  implicit def config: PlatformConfig

  val brokerNum        = config.brokerNum
  val brokerAddresses  = Array.fill[Option[InetSocketAddress]](brokerNum)(None)
  val brokerConnectors = Array.fill[Option[ActorRef]](brokerNum)(None)

  def addBrokerInfo(info: BrokerConnector.BrokerInfo, sender: ActorRef): Boolean = {
    val id = info.id.value
    if (brokerAddresses(id).isEmpty) {
      brokerAddresses(id)  = Some(info.address)
      brokerConnectors(id) = Some(sender)
      true
    } else false
  }

  def isBrokerInfoFull: Boolean = {
    !brokerAddresses.exists(_.isEmpty)
  }

  def broadcast[T](message: T): Unit = {
    brokerConnectors.foreach(_.get ! message)
  }

  protected def buildCliqueInfo: CliqueInfo = {
    val addresses = AVector.from(brokerAddresses.map(_.get))
    CliqueInfo.unsafe(CliqueId.fromBytesUnsafe(config.discoveryPublicKey.bytes),
                      addresses,
                      config.groupNumPerBroker)
  }

  val readys: Array[Boolean]  = Array.fill(brokerNum)(false)
  def setReady(id: Int): Unit = readys(id) = true
  def isAllReady: Boolean     = readys.forall(identity)

  val closeds: Array[Boolean] = Array.fill(brokerNum)(false)
  def isAllClosed: Boolean    = closeds.forall(identity)
  def setClose(actor: ActorRef): Unit = {
    val id = brokerConnectors.indexWhere(_.get == actor)
    if (id != -1) closeds(id) = true
  }
}
