package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

trait BrokerGroupInfo {
  def brokerId: Int
  def groupNumPerBroker: Int

  lazy val groupFrom: Int = brokerId * groupNumPerBroker

  lazy val groupUntil: Int = (brokerId + 1) * groupNumPerBroker

  def contains(index: GroupIndex): Boolean = containsRaw(index.value)

  def containsRaw(index: Int): Boolean = groupFrom <= index && index < groupUntil

  def intersect(another: BrokerGroupInfo): Boolean =
    BrokerInfo.intersect(groupFrom, groupUntil, another.groupFrom, another.groupUntil)

  def calIntersection(another: BrokerGroupInfo): (Int, Int) = {
    (math.max(groupFrom, another.groupFrom), math.min(groupUntil, another.groupUntil))
  }
}

final case class BrokerInfo private (
    brokerId: Int,
    groupNumPerBroker: Int,
    address: InetSocketAddress
) extends BrokerGroupInfo

object BrokerInfo extends SafeSerdeImpl[BrokerInfo, GroupConfig] { self =>
  val _serde: Serde[BrokerInfo] =
    Serde.forProduct3(unsafe, t => (t.brokerId, t.groupNumPerBroker, t.address))

  override def validate(info: BrokerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    validate(info.brokerId, info.groupNumPerBroker)
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
