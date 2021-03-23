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

import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.{CliqueConfig, GroupConfig}
import org.alephium.serde._
import org.alephium.util.AVector

// All the groups [0, ..., G-1] are divided into G/gFactor continuous groups
// Assume the peers are ordered according to the groups they correspond to
final case class CliqueInfo private (
    id: CliqueId,
    externalAddresses: AVector[Option[InetSocketAddress]],
    internalAddresses: AVector[InetSocketAddress],
    groupNumPerBroker: Int
) { self =>
  def brokerNum: Int = internalAddresses.length

  def cliqueConfig: CliqueConfig =
    new CliqueConfig {
      val brokerNum: Int = self.brokerNum
      val groups: Int    = self.brokerNum * self.groupNumPerBroker
    }

  def intraBrokers: AVector[BrokerInfo] = {
    internalAddresses.mapWithIndex { (internalAddress, index) =>
      BrokerInfo.unsafe(id, index, groupNumPerBroker, internalAddress)
    }
  }

  def coordinatorAddress: InetSocketAddress = internalAddresses.head

  def selfInterBrokerInfo(implicit brokerConfig: BrokerGroupInfo): InterBrokerInfo =
    InterBrokerInfo.unsafe(id, brokerConfig.brokerId, groupNumPerBroker)

  def selfBrokerInfo(implicit brokerConfig: BrokerGroupInfo): Option[BrokerInfo] = {
    val brokerId = brokerConfig.brokerId
    externalAddresses(brokerId) map { address =>
      BrokerInfo.unsafe(id, brokerId, groupNumPerBroker, address)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def interBrokers: Option[AVector[BrokerInfo]] = {
    Option.when(externalAddresses.forall(_.nonEmpty))(externalAddresses.mapWithIndex {
      case (addressOpt, brokerId) =>
        BrokerInfo.unsafe(id, brokerId, groupNumPerBroker, addressOpt.get)
    })
  }
}

object CliqueInfo extends SafeSerdeImpl[CliqueInfo, GroupConfig] {
  val _serde: Serde[CliqueInfo] =
    Serde.forProduct4(
      unsafe,
      t => (t.id, t.externalAddresses, t.internalAddresses, t.groupNumPerBroker)
    )

  override def validate(info: CliqueInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    val cliqueGroups = info.brokerNum * info.groupNumPerBroker
    if (cliqueGroups != config.groups) {
      Left(s"Number of groups: got: $cliqueGroups expect: ${config.groups}")
    } else {
      Right(())
    }
  }

  def unsafe(
      id: CliqueId,
      externalAddresses: AVector[Option[InetSocketAddress]],
      internalAddresses: AVector[InetSocketAddress],
      groupNumPerBroker: Int
  ): CliqueInfo = {
    new CliqueInfo(id, externalAddresses, internalAddresses, groupNumPerBroker)
  }
}

final case class InterCliqueInfo(
    id: CliqueId,
    externalAddresses: AVector[InetSocketAddress],
    groupNumPerBroker: Int
) {
  def brokerNum: Int = externalAddresses.length

  def brokers: AVector[BrokerInfo] = {
    externalAddresses.mapWithIndex { (externalAddress, index) =>
      BrokerInfo.unsafe(id, index, groupNumPerBroker, externalAddress)
    }
  }
}

object InterCliqueInfo extends SafeSerdeImpl[InterCliqueInfo, GroupConfig] {
  val _serde: Serde[InterCliqueInfo] =
    Serde.forProduct3(unsafe, t => (t.id, t.externalAddresses, t.groupNumPerBroker))

  override def validate(
      info: InterCliqueInfo
  )(implicit config: GroupConfig): Either[String, Unit] = {
    val cliqueGroup = info.brokerNum * info.groupNumPerBroker
    if (cliqueGroup != config.groups) {
      Left(s"Number of groups: got: $cliqueGroup expect: ${config.groups}")
    } else {
      Right(())
    }
  }

  def unsafe(
      id: CliqueId,
      externalAddresses: AVector[InetSocketAddress],
      groupNumPerBroker: Int
  ): InterCliqueInfo = {
    new InterCliqueInfo(id, externalAddresses, groupNumPerBroker)
  }
}
