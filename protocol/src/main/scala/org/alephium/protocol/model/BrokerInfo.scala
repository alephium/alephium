// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol._
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

trait BrokerGroupInfo {
  def brokerId: Int
  def groupNumPerBroker: Int

  lazy val groupFrom: Int = brokerId * groupNumPerBroker

  lazy val groupUntil: Int = (brokerId + 1) * groupNumPerBroker

  def groupRange: Range = groupFrom until groupUntil

  def contains(index: GroupIndex): Boolean = containsRaw(index.value)

  def containsRaw(index: Int): Boolean = groupFrom <= index && index < groupUntil

  def intersect(another: BrokerGroupInfo): Boolean =
    BrokerInfo.intersect(groupFrom, groupUntil, another.groupFrom, another.groupUntil)

  def calIntersection(another: BrokerGroupInfo): (Int, Int) = {
    (math.max(groupFrom, another.groupFrom), math.min(groupUntil, another.groupUntil))
  }
}

final case class BrokerInfo private (
    cliqueId: CliqueId,
    brokerId: Int,
    groupNumPerBroker: Int,
    address: InetSocketAddress
) extends BrokerGroupInfo {
  def peerId: PeerId = PeerId(cliqueId, brokerId)

  def interBrokerInfo: InterBrokerInfo =
    InterBrokerInfo.unsafe(cliqueId, brokerId, groupNumPerBroker)
}

object BrokerInfo extends SafeSerdeImpl[BrokerInfo, GroupConfig] { self =>
  def from(remoteAddress: InetSocketAddress, remoteBroker: InterBrokerInfo): BrokerInfo =
    unsafe(
      remoteBroker.cliqueId,
      remoteBroker.brokerId,
      remoteBroker.groupNumPerBroker,
      remoteAddress
    )

  val _serde: Serde[BrokerInfo] =
    Serde.forProduct4(unsafe, t => (t.cliqueId, t.brokerId, t.groupNumPerBroker, t.address))

  override def validate(info: BrokerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    validate(info.brokerId, info.groupNumPerBroker)
  }

  def from(cliqueId: CliqueId, brokerId: Int, groupNumPerBroker: Int, address: InetSocketAddress)(
      implicit config: GroupConfig
  ): Option[BrokerInfo] = {
    if (validate(brokerId, groupNumPerBroker).isRight) {
      Some(new BrokerInfo(cliqueId, brokerId, groupNumPerBroker, address))
    } else {
      None
    }
  }

  def unsafe(
      cliqueId: CliqueId,
      brokerId: Int,
      groupNumPerBroker: Int,
      address: InetSocketAddress
  ): BrokerInfo =
    new BrokerInfo(cliqueId, brokerId, groupNumPerBroker, address)

  def validate(id: Int, groupNumPerBroker: Int)(implicit
      config: GroupConfig
  ): Either[String, Unit] = {
    if (id < 0 || id >= config.groups) {
      Left(s"BrokerInfo - invalid id: $id")
    } else if (groupNumPerBroker <= 0 || (config.groups % groupNumPerBroker != 0)) {
      Left(s"BrokerInfo - invalid groupNumPerBroker: $groupNumPerBroker")
    } else if (id >= (config.groups / groupNumPerBroker)) {
      Left(s"BrokerInfo - invalid id: $id")
    } else {
      Right(())
    }
  }

  // Check if two segments intersect or not
  @inline def intersect(from0: Int, until0: Int, from1: Int, until1: Int): Boolean = {
    !(until0 <= from1 || until1 <= from0)
  }
}

final case class InterBrokerInfo private (
    cliqueId: CliqueId,
    brokerId: Int,
    groupNumPerBroker: Int
) extends BrokerGroupInfo {
  def peerId: PeerId = PeerId(cliqueId, brokerId)
  def hash(implicit serializer: Serializer[InterBrokerInfo]): Hash = {
    Hash.hash(serialize(this))
  }
}

object InterBrokerInfo extends SafeSerdeImpl[InterBrokerInfo, GroupConfig] {
  val _serde: Serde[InterBrokerInfo] =
    Serde.forProduct3(unsafe, t => (t.cliqueId, t.brokerId, t.groupNumPerBroker))

  def unsafe(cliqueId: CliqueId, brokerId: Int, groupNumPerBroker: Int): InterBrokerInfo =
    new InterBrokerInfo(cliqueId, brokerId, groupNumPerBroker)

  def validate(info: InterBrokerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    BrokerInfo.validate(info.brokerId, info.groupNumPerBroker)
  }
}
