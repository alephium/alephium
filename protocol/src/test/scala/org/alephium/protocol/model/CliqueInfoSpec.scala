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

import scala.util.Random

import org.scalacheck.Gen

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.util.AlephiumSpec

class CliqueInfoSpec extends AlephiumSpec with Generators {
  it should "work" in {
    forAll(Gen.chooseNum(1, 16)) { g =>
      implicit val groupConfig = new GroupConfig {
        override def groups: Int = g
      }
      val cliqueInfo = cliqueInfoGen.sample.get
      implicit val brokerConfig = new BrokerConfig {
        val groups: Int    = g
        val brokerNum: Int = cliqueInfo.brokerNum
        val brokerId: Int  = Random.nextInt(brokerNum)
      }

      val intraBrokers = cliqueInfo.intraBrokers
      intraBrokers.foreachWithIndex { case (broker, index) =>
        broker.cliqueId is cliqueInfo.id
        broker.brokerId is index
        broker.brokerNum is brokerConfig.brokerNum
        broker.address is cliqueInfo.internalAddresses(index)
      }

      val selfInterBrokerInfo = cliqueInfo.selfInterBrokerInfo
      selfInterBrokerInfo.cliqueId is cliqueInfo.id
      selfInterBrokerInfo.brokerId is brokerConfig.brokerId
      selfInterBrokerInfo.brokerNum is brokerConfig.brokerNum

      val selfBrokerInfo = cliqueInfo.selfBrokerInfo.get
      selfBrokerInfo.cliqueId is cliqueInfo.id
      selfBrokerInfo.brokerId is brokerConfig.brokerId
      selfBrokerInfo.brokerNum is brokerConfig.brokerNum
      selfBrokerInfo.address is cliqueInfo.externalAddresses(brokerConfig.brokerId).get

      val brokerInfos = cliqueInfo.interBrokers.get
      brokerInfos.foreachWithIndex { case (brokerInfo, index) =>
        brokerInfo.cliqueId is cliqueInfo.id
        brokerInfo.brokerId is index
        brokerInfo.brokerNum is brokerConfig.brokerNum
        brokerInfo.address is cliqueInfo.externalAddresses(index).get
      }
    }
  }
}
