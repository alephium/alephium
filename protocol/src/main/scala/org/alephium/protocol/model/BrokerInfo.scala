package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

final case class BrokerInfo private (
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

object BrokerInfo extends SafeSerdeImpl[BrokerInfo, GroupConfig] { self =>
  val _serde: Serde[BrokerInfo] =
    Serde.forProduct3(unsafe, t => (t.id, t.groupNumPerBroker, t.address))

  override def validate(info: BrokerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    validate(info.id, info.groupNumPerBroker)
  }

  def from(id: Int, groupNumPerBroker: Int, address: InetSocketAddress)(
      implicit config: GroupConfig): Option[BrokerInfo] = {
    if (validate(id, groupNumPerBroker).isRight)
      Some(new BrokerInfo(id, groupNumPerBroker, address))
    else None
  }

  def unsafe(id: Int, groupNumPerBroker: Int, address: InetSocketAddress): BrokerInfo =
    new BrokerInfo(id, groupNumPerBroker, address)

  def validate(id: Int, groupNumPerBroker: Int)(
      implicit config: GroupConfig): Either[String, Unit] = {
    if (id < 0 || id >= config.groups) Left(s"BrokerInfo - invalid id: $id")
    else if (groupNumPerBroker <= 0 || (config.groups % groupNumPerBroker != 0))
      Left(s"BrokerInfo - invalid groupNumPerBroker: $groupNumPerBroker")
    else if (id >= (config.groups / groupNumPerBroker))
      Left(s"BrokerInfo - invalid id: $id")
    else Right(())
  }

  // Check if two segments intersect or not
  @inline def intersect(from0: Int, until0: Int, from1: Int, until1: Int): Boolean = {
    !(until0 <= from1 || until1 <= from0)
  }
}
