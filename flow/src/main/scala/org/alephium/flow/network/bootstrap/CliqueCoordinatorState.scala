package org.alephium.flow.network.bootstrap

import akka.actor.ActorRef

import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.model.CliqueId
import org.alephium.util.AVector

trait CliqueCoordinatorState {
  implicit def config: PlatformConfig

  val brokerNum        = config.brokerNum
  val brokerInfos      = Array.fill[Option[PeerInfo]](brokerNum)(None)
  val brokerConnectors = Array.fill[Option[ActorRef]](brokerNum)(None)

  def addBrokerInfo(info: PeerInfo, sender: ActorRef): Boolean = {
    val id = info.id
    if (id != config.brokerInfo.id &&
        info.groupNumPerBroker == config.groupNumPerBroker &&
        brokerInfos(id).isEmpty) {
      brokerInfos(id)      = Some(info)
      brokerConnectors(id) = Some(sender)
      true
    } else false
  }

  def isBrokerInfoFull: Boolean = {
    brokerInfos.zipWithIndex.forall {
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
  protected def buildCliqueInfo: IntraCliqueInfo = {
    val infos = AVector.tabulate(config.brokerNum) { i =>
      if (i == config.brokerInfo.id) PeerInfo.self else brokerInfos(i).get
    }
    assume(infos.length * config.groupNumPerBroker == config.groups)
    IntraCliqueInfo.unsafe(CliqueId.unsafe(config.discoveryPublicKey.bytes),
                           infos,
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
