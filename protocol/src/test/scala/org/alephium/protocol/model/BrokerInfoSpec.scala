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

import org.scalacheck.Gen

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{BrokerConfig, CliqueConfig, GroupConfig, GroupConfigFixture}
import org.alephium.util.AlephiumSpec

class BrokerInfoSpec extends AlephiumSpec {
  it should "check if group included" in {
    forAll(Gen.oneOf(2 to 1 << 4)) { _groups =>
      new Generators {
        implicit val config = new GroupConfig { override def groups: Int = _groups }
        forAll(groupNumPerBrokerGen) { _groupNumPerBroker =>
          implicit val cliqueConfig = new CliqueConfig {
            override def brokerNum: Int = groups / _groupNumPerBroker
            override def groups: Int    = _groups
          }
          forAll(brokerInfoGen) { brokerInfo =>
            val count = (0 until _groups).count(brokerInfo.containsRaw)
            count is BrokerConfig.range(brokerInfo.brokerId, _groups, brokerInfo.brokerNum).length
          }
        }
      }
    }
  }

  it should "check if id is valid" in new GroupConfigFixture with Generators { self =>
    override def groups: Int = 4
    implicit override lazy val groupConfig: GroupConfig = new GroupConfig {
      override def groups: Int = self.groups
    }
    forAll(groupNumPerBrokerGen) { _groupNumPerBroker =>
      val cliqueConfig = new CliqueConfig {
        override def brokerNum: Int = groups / _groupNumPerBroker
        override val groups: Int    = self.groups
      }
      0 until cliqueConfig.brokerNum foreach { id =>
        BrokerInfo.validate(id, groups / _groupNumPerBroker).isRight is true
      }
      cliqueConfig.brokerNum until (2 * cliqueConfig.brokerNum) foreach { id =>
        BrokerInfo.validate(id, groups / _groupNumPerBroker)(cliqueConfig).isRight is false
      }
      -cliqueConfig.brokerNum until 0 foreach { id =>
        BrokerInfo.validate(id, groups / _groupNumPerBroker)(cliqueConfig).isRight is false
      }
    }
  }

  it should "check intersection" in {
    BrokerInfo.intersect(0, 1, 1, 2) is false
    BrokerInfo.intersect(0, 1, 0, 2) is true
    BrokerInfo.intersect(1, 2, 0, 2) is true
    BrokerInfo.intersect(1, 2, 0, 1) is false
  }

  it should "test `isIncomingChain`" in new Generators with GroupConfigFixture.Default {
    val address    = socketAddressGen.sample.get
    val brokerInfo = BrokerInfo.unsafe(CliqueId.generate, 1, 3, address)
    for {
      from <- groupConfig.cliqueGroups
      to   <- groupConfig.cliqueGroups
    } {
      val index = ChainIndex(from, to)
      if (index == ChainIndex.unsafe(0, 1) || index == ChainIndex.unsafe(2, 1)) {
        brokerInfo.isIncomingChain(index) is true
      } else {
        brokerInfo.isIncomingChain(index) is false
      }
    }
  }
}
