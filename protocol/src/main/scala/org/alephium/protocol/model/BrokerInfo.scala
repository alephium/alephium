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
  def brokerNum: Int

  @inline def groupIndexOfBroker(group: GroupIndex): Int = groupIndexOfBrokerUnsafe(group.value)
  @inline def groupIndexOfBrokerUnsafe(group: Int): Int  = group / brokerNum
  @inline def brokerIndex(group: GroupIndex): Int        = brokerIndexUnsafe(group.value)
  @inline def brokerIndexUnsafe(group: Int): Int         = group % brokerNum

  def contains(index: GroupIndex): Boolean = containsRaw(index.value)

  def containsRaw(group: Int): Boolean = brokerIndexUnsafe(group) == brokerId

  def intersect(another: BrokerGroupInfo): Boolean = {
    if (brokerNum == another.brokerNum) {
      brokerId == another.brokerId
    } else if (brokerNum < another.brokerNum) {
      (another.brokerNum % brokerNum == 0) && (another.brokerId % brokerNum == brokerId)
    } else {
      (brokerNum % another.brokerNum == 0) && (brokerId % another.brokerNum == another.brokerId)
    }
  }
}

final case class BrokerInfo private (
    cliqueId: CliqueId,
    brokerId: Int,
    brokerNum: Int,
    address: InetSocketAddress
) extends BrokerGroupInfo {
  def peerId: PeerId = PeerId(cliqueId, brokerId)

  def interBrokerInfo: InterBrokerInfo =
    InterBrokerInfo.unsafe(cliqueId, brokerId, brokerNum)
}

object BrokerInfo {
  def from(remoteAddress: InetSocketAddress, remoteBroker: InterBrokerInfo): BrokerInfo =
    unsafe(
      remoteBroker.cliqueId,
      remoteBroker.brokerId,
      remoteBroker.brokerNum,
      remoteAddress
    )

  val serde: Serde[BrokerInfo] =
    Serde.forProduct4(unsafe, t => (t.cliqueId, t.brokerId, t.brokerNum, t.address))

  def validate(info: BrokerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    validate(info.brokerId, info.brokerNum)
  }

  def unsafe(
      cliqueId: CliqueId,
      brokerId: Int,
      brokerNum: Int,
      address: InetSocketAddress
  ): BrokerInfo =
    new BrokerInfo(cliqueId, brokerId, brokerNum, address)

  def validate(id: Int, brokerNum: Int)(implicit
      config: GroupConfig
  ): Either[String, Unit] = {
    if (id < 0 || id >= brokerNum) {
      Left(s"BrokerInfo - invalid id: $id")
    } else if (brokerNum > config.groups || (config.groups % brokerNum != 0)) {
      Left(s"BrokerInfo - invalid brokerNum: $brokerNum")
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
    brokerNum: Int
) extends BrokerGroupInfo {
  def peerId: PeerId = PeerId(cliqueId, brokerId)
  def hash(implicit serializer: Serializer[InterBrokerInfo]): Hash = {
    Hash.hash(serialize(this))
  }
}

object InterBrokerInfo {
  val serde: Serde[InterBrokerInfo] =
    Serde.forProduct3(unsafe, t => (t.cliqueId, t.brokerId, t.brokerNum))

  def unsafe(cliqueId: CliqueId, brokerId: Int, groupNumPerBroker: Int): InterBrokerInfo =
    new InterBrokerInfo(cliqueId, brokerId, groupNumPerBroker)

  def validate(info: InterBrokerInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    BrokerInfo.validate(info.brokerId, info.brokerNum)
  }
}
