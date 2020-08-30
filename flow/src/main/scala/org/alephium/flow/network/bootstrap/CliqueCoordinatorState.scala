package org.alephium.flow.network.bootstrap

import akka.actor.ActorRef

import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.config.{BrokerConfig, DiscoveryConfig}
import org.alephium.protocol.model.CliqueId
import org.alephium.util.AVector

trait CliqueCoordinatorState {
  implicit def brokerConfig: BrokerConfig
  implicit def networkSetting: NetworkSetting
  implicit def discoveryConfig: DiscoveryConfig

  val brokerNum        = brokerConfig.brokerNum
  val brokerInfos      = Array.fill[Option[PeerInfo]](brokerNum)(None)
  val brokerConnectors = Array.fill[Option[ActorRef]](brokerNum)(None)

  def addBrokerInfo(info: PeerInfo, sender: ActorRef): Boolean = {
    val id = info.id
    if (id != brokerConfig.brokerId &&
        info.groupNumPerBroker == brokerConfig.groupNumPerBroker &&
        brokerInfos(id).isEmpty) {
      brokerInfos(id)      = Some(info)
      brokerConnectors(id) = Some(sender)
      true
    } else false
  }

  def isBrokerInfoFull: Boolean = {
    brokerInfos.zipWithIndex.forall {
      case (opt, idx) => opt.nonEmpty || idx == brokerConfig.brokerId
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def broadcast[T](message: T): Unit = {
    brokerConnectors.zipWithIndex.foreach {
      case (opt, idx) => if (idx != brokerConfig.brokerId) opt.get ! message
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  protected def buildCliqueInfo(): IntraCliqueInfo = {
    val infos = AVector.tabulate(brokerConfig.brokerNum) { i =>
      if (i == brokerConfig.brokerId) PeerInfo.self else brokerInfos(i).get
    }
    assume(infos.length * brokerConfig.groupNumPerBroker == brokerConfig.groups)
    IntraCliqueInfo.unsafe(CliqueId.unsafe(discoveryConfig.discoveryPublicKey.bytes),
                           infos,
                           brokerConfig.groupNumPerBroker)
  }

  val readys: Array[Boolean] = {
    val result = Array.fill(brokerNum)(false)
    result(brokerConfig.brokerId) = true
    result
  }
  def isAllReady: Boolean     = readys.forall(identity)
  def setReady(id: Int): Unit = readys(id) = true

  val closeds: Array[Boolean] = {
    val result = Array.fill(brokerNum)(false)
    result(brokerConfig.brokerId) = true
    result
  }
  def isAllClosed: Boolean = closeds.forall(identity)
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def setClose(actor: ActorRef): Unit = {
    val id = brokerConnectors.indexWhere(opt => opt.nonEmpty && opt.get == actor)
    if (id != -1) closeds(id) = true
  }
}
