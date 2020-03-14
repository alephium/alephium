package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

sealed abstract case class BrokerInfo(
    id: Int,
    groupNumPerBroker: Int,
    address: InetSocketAddress
) {
  val groupFrom: Int = id * groupNumPerBroker

  val groupUntil: Int = (id + 1) * groupNumPerBroker

  def contains(index: GroupIndex): Boolean = containsRaw(index.value)

  def containsRaw(index: Int): Boolean = groupFrom <= index && index < groupUntil

  def intersect(another: BrokerInfo): Boolean =
    BrokerInfo.intersect(groupFrom, groupUntil, another.groupFrom, another.groupUntil)

  def calIntersection(another: BrokerInfo): (Int, Int) = {
    (math.max(groupFrom, another.groupFrom), math.min(groupUntil, another.groupUntil))
  }

  override def toString: String = s"BrokerInfo($id, $groupNumPerBroker, $address)"

  override def equals(obj: Any): Boolean = obj match {
    case that: BrokerInfo =>
      id == that.id && groupNumPerBroker == that.groupNumPerBroker && address == that.address
    case _ => false
  }

  override def hashCode(): Int = {
    id.hashCode() ^ groupNumPerBroker ^ address.hashCode()
  }
}

object BrokerInfo { self =>
  implicit val serializer: Serializer[BrokerInfo] =
    Serializer.forProduct3(t => (t.id, t.groupNumPerBroker, t.address))

  def from(id: Int, groupNumPerBroker: Int, address: InetSocketAddress)(
      implicit config: GroupConfig): Option[BrokerInfo] = {
    if (validate(id, groupNumPerBroker)) Some(new BrokerInfo(id, groupNumPerBroker, address) {})
    else None
  }

  def unsafe(id: Int, groupNumPerBroker: Int, address: InetSocketAddress): BrokerInfo =
    new BrokerInfo(id, groupNumPerBroker, address) {}

  class Unsafe(val id: Int, val groupNumPerBroker: Int, val address: InetSocketAddress)
      extends UnsafeModel[BrokerInfo] {
    def validate(implicit config: GroupConfig): Either[String, BrokerInfo] = {
      if (self.validate(id, groupNumPerBroker)) {
        Right(new BrokerInfo(id, groupNumPerBroker, address) {})
      } else {
        val groups = config.groups
        Left(s"Invalid broker info: id: $id, groupNumPerBroker: $groupNumPerBroker groups: $groups")
      }
    }
  }

  object Unsafe {
    implicit val serde: Serde[Unsafe] =
      Serde.forProduct3(new Unsafe(_, _, _), t => (t.id, t.groupNumPerBroker, t.address))
  }

  def validate(id: Int, groupNumPerBroker: Int)(implicit config: GroupConfig): Boolean = {
    0 <= id && (config.groups % groupNumPerBroker == 0) && {
      val brokerNum = config.groups / groupNumPerBroker
      id < brokerNum
    }
  }

  // Check if two segments intersect or not
  @inline def intersect(from0: Int, until0: Int, from1: Int, until1: Int): Boolean = {
    !(until0 <= from1 || until1 <= from0)
  }
}
