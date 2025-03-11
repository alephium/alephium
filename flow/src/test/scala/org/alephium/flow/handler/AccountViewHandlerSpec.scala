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

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.util.{AlephiumActorSpec, TimeStamp}

class AccountViewHandlerSpec extends AlephiumActorSpec {
  trait Fixture extends FlowFixture {
    lazy val accountViewHandler =
      newTestActorRef[AccountViewHandler](AccountViewHandler.props(blockFlow))

    def setSynced() = {
      accountViewHandler ! InterCliqueManager.SyncedResult(true)
      eventually(accountViewHandler.underlyingActor.isNodeSynced is true)
    }

    lazy val groupIndex = GroupIndex.unsafe(0)
    lazy val block = {
      val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 1))
      blockFlow.add(block, None)
      block
    }
    lazy val flowData = ChainHandler.FlowDataAdded(block, DataOrigin.Local, TimeStamp.now())
  }

  it should "ignore the flow data if the node is not in sync" in new Fixture {
    accountViewHandler.underlyingActor.isNodeSynced is false
    accountViewHandler ! flowData
    eventually(blockFlow.getAccountView(groupIndex).outBlocks.contains(block) is false)
  }

  it should "ignore the flow data if it is not related to our broker" in new Fixture { Self =>
    setSynced()

    val newConfigFixture = new AlephiumConfigFixture {
      override val configValues: Map[String, Any] = Map(("alephium.broker.broker-id", 1))
      override lazy val genesisKeys               = Self.genesisKeys
    }

    val anotherConfig   = newConfigFixture.config
    val anotherStorages = StoragesFixture.buildStorages(newConfigFixture.rootPath)
    val blockFlow1      = BlockFlow.fromGenesisUnsafe(anotherConfig, anotherStorages)
    val block1          = emptyBlock(blockFlow1, ChainIndex.unsafe(1, 1))
    block1.chainIndex.relateTo(brokerConfig) is false

    val accountView0 = blockFlow.getAccountView(groupIndex)
    accountViewHandler ! flowData.copy(data = block1)
    eventually(blockFlow.getAccountView(groupIndex) is accountView0)
  }

  it should "ignore the block header" in new Fixture {
    setSynced()
    accountViewHandler ! flowData.copy(data = block.header)
    eventually(blockFlow.getAccountView(groupIndex).outBlocks.contains(block) is false)
  }

  it should "update the account view since danube" in new Fixture {
    setSynced()
    val accountView = blockFlow.getAccountView(groupIndex)
    accountView.outBlocks.contains(block) is false
    accountViewHandler ! flowData
    eventually(blockFlow.getAccountView(groupIndex).outBlocks.contains(block) is true)
  }

  it should "update the account view if the danube upgrade will be activated soon" in new Fixture {
    val now = TimeStamp.now()
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.rhone-hard-fork-timestamp", now.millis),
      ("alephium.network.danube-hard-fork-timestamp", now.plusMinutesUnsafe(1).millis)
    )
    setSynced()

    val accountView = blockFlow.getAccountView(groupIndex)
    accountView.outBlocks.contains(block) is false
    accountViewHandler ! flowData
    eventually(blockFlow.getAccountView(groupIndex).outBlocks.contains(block) is true)
  }

  it should "not update the account view if the danube upgrade will not be activated soon" in new Fixture {
    val now = TimeStamp.now()
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.rhone-hard-fork-timestamp", now.millis),
      ("alephium.network.danube-hard-fork-timestamp", now.plusMinutesUnsafe(3).millis)
    )
    setSynced()

    val accountView = blockFlow.getAccountView(groupIndex)
    accountView.outBlocks.contains(block) is false
    accountViewHandler ! flowData
    eventually(blockFlow.getAccountView(groupIndex).outBlocks.contains(block) is false)
  }
}
