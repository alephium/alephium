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

package org.alephium.protocol.config

import scala.util.Random

import org.alephium.protocol.model.{BrokerGroupInfo, ChainIndex, GroupIndex}
import org.alephium.util.AVector

trait BrokerConfig extends GroupConfig with CliqueConfig with BrokerGroupInfo {
  def brokerId: Int

  lazy val groupNumPerBroker: Int = groups / brokerNum

  lazy val groupRange: Range = BrokerConfig.range(brokerId, groups, brokerNum)

  def randomGroupIndex(): GroupIndex = {
    GroupIndex.unsafe(groupRange(Random.nextInt(groupRange.size)))(this)
  }

  @inline def remoteRange(remote: BrokerGroupInfo): Range =
    BrokerConfig.range(remote.brokerId, groups, remote.brokerNum)

  @inline def remoteGroupNum(remote: BrokerGroupInfo): Int = groups / remote.brokerNum

  lazy val chainIndexes: AVector[ChainIndex] =
    AVector.from(
      for {
        from <- groupRange
        to   <- 0 until groups
      } yield ChainIndex.unsafe(from, to)(this)
    )

  def calIntersection(another: BrokerGroupInfo): Range = {
    if (brokerNum == another.brokerNum) {
      if (brokerId == another.brokerId) groupRange else BrokerConfig.empty
    } else if (brokerNum < another.brokerNum) {
      if ((another.brokerNum % brokerNum == 0) && (another.brokerId % brokerNum == brokerId)) {
        Range(another.brokerId, groups, another.brokerNum)
      } else {
        BrokerConfig.empty
      }
    } else {
      if (
        (brokerNum % another.brokerNum == 0) && (brokerId % another.brokerNum == another.brokerId)
      ) {
        groupRange
      } else {
        BrokerConfig.empty
      }
    }
  }
}

object BrokerConfig {
  val empty: Range = Range(0, -1)

  @inline def range(brokerId: Int, groups: Int, brokerNum: Int): Range =
    Range(brokerId, groups, brokerNum)
}
