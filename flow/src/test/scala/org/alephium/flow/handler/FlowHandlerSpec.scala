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
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.AVector

class FlowHandlerSpec extends AlephiumFlowActorSpec with NoIndexModelGeneratorsLike {
  trait Fixture {
    val groupNum = 6

    val brokerGroupInfo0 = new BrokerConfig {
      override def groups: Int    = groupNum
      override def brokerId: Int  = 1
      override def brokerNum: Int = 2
    }
    val brokerGroupInfo1 = new BrokerGroupInfo {
      override def brokerId: Int  = 2
      override def brokerNum: Int = 3
    }
    val brokerGroupInfo2 = new BrokerGroupInfo {
      override def brokerId: Int  = 1
      override def brokerNum: Int = 3
    }
    val brokerGroupInfo3 = new BrokerGroupInfo {
      override def brokerId: Int  = 0
      override def brokerNum: Int = 3
    }
  }

  it should "calculate locators" in new Fixture {
    val locatorsWithIndex = AVector.tabulate(3 * 6) { k =>
      ChainIndex.unsafe(k / 6 * 2 + 1, k % 6)(brokerGroupInfo0) ->
        AVector.fill(k)(BlockHash.generate)
    }
    val flowEvent = FlowHandler.SyncLocators(locatorsWithIndex)
    val locators  = locatorsWithIndex.map(_._2)
    flowEvent.filterFor(brokerGroupInfo0) is locators
    flowEvent.filterFor(brokerGroupInfo1) is locators.takeRight(groupNum)
    flowEvent.filterFor(brokerGroupInfo2) is locators.take(groupNum)
    flowEvent.filterFor(brokerGroupInfo3) is locators.drop(groupNum).take(groupNum)
  }

  it should "filter chain tips" in new Fixture {
    val groupConfig: GroupConfig = new GroupConfig { val groups: Int = groupNum }
    val chainTips                = AVector.fill(3 * 6)(ChainTip(BlockHash.generate, 1, Weight.zero))
    val chainTipsWithGroup = (0 until groupConfig.groups).map { index =>
      val groupIndex = GroupIndex.unsafe(index)(groupConfig)
      chainTips.filter(_.chainIndex(groupConfig).from == groupIndex)
    }

    def getTips(groups: Seq[Int]): Set[ChainTip] =
      groups.flatMap(index => chainTipsWithGroup(index)).toSet

    val flowEvent = FlowHandler.UpdateChainState(chainTips)
    flowEvent.filterFor(brokerGroupInfo0).toSet is getTips(Seq(1, 3, 5))
    flowEvent.filterFor(brokerGroupInfo1).toSet is getTips(Seq(2, 5))
    flowEvent.filterFor(brokerGroupInfo2).toSet is getTips(Seq(1, 4))
    flowEvent.filterFor(brokerGroupInfo3).toSet is getTips(Seq(0, 3))
  }
}
