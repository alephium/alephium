package org.alephium.flow.network.bootstrap

import java.net.InetSocketAddress

import akka.actor.ActorRef

import org.alephium.flow.platform.PlatformProfile
import org.alephium.protocol.model.{BrokerInfo, CliqueId, CliqueInfo}
import org.alephium.util.AVector

trait CliqueCoordinatorState {
  implicit def config: PlatformProfile

  val brokerNum        = config.brokerNum
  val brokerAddresses  = Array.fill[Option[InetSocketAddress]](brokerNum)(None)
  val brokerConnectors = Array.fill[Option[ActorRef]](brokerNum)(None)

  def addBrokerInfo(info: BrokerInfo, sender: ActorRef): Boolean = {
    val id = info.id
    if (id != config.brokerInfo.id &&
        info.groupNumPerBroker == config.groupNumPerBroker &&
        brokerAddresses(id).isEmpty) {
      brokerAddresses(id)  = Some(info.address)
      brokerConnectors(id) = Some(sender)
      true
    } else false
  }

  def isBrokerInfoFull: Boolean = {
    brokerAddresses.zipWithIndex.forall {
      case (opt, idx) => opt.nonEmpty || idx == config.brokerInfo.id
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def broadcast[T](message: T): Unit = {
    brokerConnectors.zipWithIndex.foreach {
      case (opt, idx) => if (idx != config.brokerInfo.id) opt.get ! message
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  protected def buildCliqueInfo: CliqueInfo = {
    val addresses = AVector.tabulate(config.brokerNum) { i =>
      if (i == config.brokerInfo.id) config.publicAddress else brokerAddresses(i).get
    }
    CliqueInfo.unsafe(CliqueId.fromBytesUnsafe(config.discoveryPublicKey.bytes),
                      addresses,
                      config.groupNumPerBroker)
  }

  val readys: Array[Boolean] = {
    val result = Array.fill(brokerNum)(false)
    result(config.brokerInfo.id) = true
    result
  }
  def isAllReady: Boolean     = readys.forall(identity)
  def setReady(id: Int): Unit = readys(id) = true

  val closeds: Array[Boolean] = {
    val result = Array.fill(brokerNum)(false)
    result(config.brokerInfo.id) = true
    result
  }
  def isAllClosed: Boolean = closeds.forall(identity)
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def setClose(actor: ActorRef): Unit = {
    val id = brokerConnectors.indexWhere(opt => opt.nonEmpty && opt.get == actor)
    if (id != -1) closeds(id) = true
  }
}
