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

package org.alephium.flow.handler

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerGroupInfo, NoIndexModelGeneratorsLike}
import org.alephium.util.AVector

class FlowHandlerSpec extends AlephiumFlowActorSpec("FlowHandler") with NoIndexModelGeneratorsLike {
  it should "calculate locators" in {
    val groupNum = 6

    val brokerGroupInfo0 = new BrokerConfig {
      override def groups: Int    = groupNum
      override def brokerId: Int  = 1
      override def brokerNum: Int = 2
    }
    val brokerGroupInfo1 = new BrokerGroupInfo {
      override def brokerId: Int          = 2
      override def groupNumPerBroker: Int = 2
    }
    val brokerGroupInfo2 = new BrokerGroupInfo {
      override def brokerId: Int          = 1
      override def groupNumPerBroker: Int = 2
    }
    val brokerGroupInfo3 = new BrokerGroupInfo {
      override def brokerId: Int          = 0
      override def groupNumPerBroker: Int = 2
    }

    val locators = AVector.tabulate(3 * 6) { k =>
      AVector.fill(k)(BlockHash.generate)
    }
    val flowEvent = FlowHandler.SyncLocators(brokerGroupInfo0, locators)
    flowEvent.filerFor(brokerGroupInfo0) is locators
    flowEvent.filerFor(brokerGroupInfo1) is locators.takeRight(2 * groupNum)
    flowEvent.filerFor(brokerGroupInfo2) is locators.take(1 * groupNum)
    flowEvent.filerFor(brokerGroupInfo3) is locators.take(0 * groupNum)
  }
}
