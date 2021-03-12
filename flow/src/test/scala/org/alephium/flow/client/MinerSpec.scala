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
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, FlowHandler, TestUtils}
import org.alephium.protocol.model.{ChainIndex, GroupIndex, LockupScriptGenerators}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{ActorRefT, AVector, Duration, TimeStamp}

class MinerSpec extends AlephiumFlowActorSpec("Miner") with ScalaFutures {
  implicit val askTimeout: Timeout = Timeout(Duration.ofSecondsUnsafe(10).asScala)
  it should "use proper timestamp" in {
    val currentTs = TimeStamp.now()
    val pastTs    = currentTs.minusUnsafe(Duration.ofHoursUnsafe(1))
    val futureTs  = currentTs.plusHoursUnsafe(1)

    Thread.sleep(10) // wait until TimStamp.now() > currentTs
    Miner.nextTimeStamp(pastTs) > currentTs is true
    Miner.nextTimeStamp(currentTs) > currentTs is true
    Miner.nextTimeStamp(futureTs) is futureTs.plusMillisUnsafe(1)
  }

  it should "initialize FairMiner" in {
    val flowHandler          = TestProbe("flowHandler")
    val blockFlow: BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val allHandlers: AllHandlers =
      AllHandlers.buildWithFlowHandler(system, blockFlow, ActorRefT(flowHandler.ref), "")

    val miner = system.actorOf(Miner.props(config.minerAddresses, blockFlow, allHandlers))

    miner ! Miner.Start
    flowHandler.expectMsgType[FlowHandler.SetHandler]
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
    val miner            = system.actorOf(Miner.props(config.minerAddresses, blockFlow, allHandlers))

    miner ! Miner.MiningResult(None, ChainIndex.unsafe(0, 0), 0)
  }

  it should "handle its addresses" in new LockupScriptGenerators {
    val groupConfig      = config.broker
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createBlockHandlersProbe
    val miner            = system.actorOf(Miner.props(config.minerAddresses, blockFlow, allHandlers))

    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .futureValue is config.minerAddresses

    val lockupScripts =
      AVector.tabulate(groupConfig.groups)(i => addressGen(GroupIndex.unsafe(i)).sample.get._1)

    miner ! Miner.UpdateAddresses(lockupScripts)

    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .futureValue is lockupScripts

    miner ! Miner.UpdateAddresses(AVector.empty)

    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .futureValue is lockupScripts
  }
}
