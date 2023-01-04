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

import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.util.{AlephiumSpec, AVector}

class BrokerConfigSpec extends AlephiumSpec {
  it should "work properly" in {
    val config0 = buildConfig(0, 1, 12)
    config0.groupRange is Range(0, 12)
    config0.groupNumPerBroker is 12
    config0.groupIndexOfBrokerUnsafe(7) is 7
    config0.brokerIndexUnsafe(7) is 0

    val config1 = buildConfig(2, 4, 12)
    config1.groupRange is Range(2, 12, 4)
    config1.groupNumPerBroker is 3
    config1.groupIndexOfBrokerUnsafe(6) is 1
    config1.brokerIndexUnsafe(7) is 3

    val config2 = buildConfig(6, 12, 12)
    config2.groupRange is Range(6, 12, 12)
    config2.groupNumPerBroker is 1
    config2.groupIndexOfBrokerUnsafe(6) is 0
    config2.brokerIndexUnsafe(7) is 7
  }

  it should "calculate intersection" in {
    def test0(a: BrokerConfig, b: BrokerConfig, range: Range) = {
      a.remoteRange(b) is b.groupRange
      a.remoteGroupNum(b) is b.groupNumPerBroker
      a.intersect(b) is true
      a.calIntersection(b) is range
    }

    def test1(a: BrokerConfig, b: BrokerConfig) = {
      a.remoteRange(b) is b.groupRange
      a.remoteGroupNum(b) is b.groupNumPerBroker
      a.intersect(b) is false
      a.calIntersection(b).isEmpty is true
    }

    val config0 = buildConfig(0, 1, 12)
    val config1 = buildConfig(2, 4, 12)
    val config2 = buildConfig(1, 3, 12)
    val config3 = buildConfig(6, 12, 12)
    val config4 = buildConfig(7, 12, 12)
    test0(config0, config0, config0.groupRange)
    test0(config0, config1, config1.groupRange)
    test0(config0, config2, config2.groupRange)
    test0(config0, config3, config3.groupRange)
    test0(config1, config0, config1.groupRange)
    test0(config1, config1, config1.groupRange)
    test1(config1, config2)
    test0(config1, config3, config3.groupRange)
    test0(config2, config0, config2.groupRange)
    test1(config2, config1)
    test0(config2, config2, config2.groupRange)
    test1(config2, config3)
    test0(config3, config0, config3.groupRange)
    test0(config3, config1, config3.groupRange)
    test1(config3, config2)
    test0(config3, config3, config3.groupRange)
    test1(config3, config4)
  }

  it should "calculate chain indexes" in {
    val config0 = buildConfig(1, 2, 4)
    config0.cliqueChainIndexes is AVector.from(for {
      from <- 0 until 4
      to   <- 0 until 4
    } yield ChainIndex.unsafe(from, to)(config0))
    config0.chainIndexes is AVector.from(for {
      from <- Seq(1, 3)
      to   <- 0 until 4
    } yield ChainIndex.unsafe(from, to)(config0))
  }

  it should "calculate correct group indexes" in {
    val config = buildConfig(1, 2, 4)
    config.cliqueGroupIndexes is AVector.tabulate(4)(GroupIndex.unsafe(_)(config))
    config.groupRange.toList is List(1, 3)
  }

  def buildConfig(_brokerId: Int, _brokerNum: Int, _groups: Int): BrokerConfig = new BrokerConfig {
    def brokerId: Int  = _brokerId
    def groups: Int    = _groups
    def brokerNum: Int = _brokerNum
  }
}
