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

package org.alephium.flow.client

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils}
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.{ActorRefT, Duration, TimeStamp}

class MinerSpec extends AlephiumFlowActorSpec("Miner") {
  it should "use proper timestamp" in {
    val currentTs = TimeStamp.now()
    val pastTs    = currentTs.minusUnsafe(Duration.ofHoursUnsafe(1))
    val futureTs  = currentTs.plusHoursUnsafe(1)

    Thread.sleep(10)
    Miner.nextTimeStamp(pastTs) > currentTs is true
    Miner.nextTimeStamp(currentTs) > currentTs is true
    Miner.nextTimeStamp(futureTs) is futureTs.plusMillisUnsafe(1)
  }

  it should "initialize FairMiner" in {
    val flowHandler          = TestProbe("flowHandler")
    val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val allHandlers: AllHandlers =
      AllHandlers.buildWithFlowHandler(system, blockFlow, ActorRefT(flowHandler.ref))

    val miner = system.actorOf(Miner.props(blockFlow, allHandlers))

    miner ! Miner.Start
    flowHandler.expectMsgType[FlowHandler.Register]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]

    miner ! Miner.Stop
    flowHandler.expectMsgType[FlowHandler.UnRegister.type]

    miner ! Miner.Start
    flowHandler.expectMsgType[FlowHandler.Register]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
    flowHandler.expectMsgType[FlowHandler.AddBlock]
  }

  it should "ignore handled mining result when it's stopped" in {
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createBlockHandlersProbe
    val miner            = system.actorOf(Miner.props(blockFlow, allHandlers))

    miner ! Miner.MiningResult(None, ChainIndex.unsafe(0, 0), 0)
  }
}
