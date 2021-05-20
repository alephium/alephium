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

import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{BlockChainHandler, TestUtils}
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex, LockupScriptGenerators}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Duration, TimeStamp}

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

  it should "initialize FairMiner" in new FlowFixture {
    override val configValues =
      Map(("alephium.broker.groups", 1), ("alephium.broker.broker-num", 1))

    val (allHandlers, allHandlersProbes) = TestUtils.createBlockHandlersProbe
    val blockHandlerProbe                = allHandlersProbes.blockHandlers(ChainIndex.unsafe(0, 0))

    val miner = system.actorOf(
      Miner.props(config.network.networkType, config.minerAddresses, blockFlow, allHandlers)
    )

    def checkMining(isMining: Boolean) = {
      miner ! Miner.IsMining
      expectMsg(isMining)
    }

    def awaitForBlocks(n: Int) = {
      (0 until n).foreach { _ =>
        val block = blockHandlerProbe.expectMsgType[BlockChainHandler.Validate].block
        miner ! BlockChainHandler.BlockAdded(block.hash)
      }
    }

    checkMining(false)

    miner ! Miner.Start
    checkMining(true)
    awaitForBlocks(3)
    val block = blockHandlerProbe.expectMsgType[BlockChainHandler.Validate].block

    miner ! Miner.Stop
    checkMining(false)
    miner ! BlockChainHandler.BlockAdded(block.hash)
    blockHandlerProbe.expectNoMessage()

    miner ! Miner.Start
    awaitForBlocks(3)
  }

  it should "ignore handled mining result when it's stopped" in {
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createBlockHandlersProbe
    val miner = system.actorOf(
      Miner.props(config.network.networkType, config.minerAddresses, blockFlow, allHandlers)
    )

    miner ! Miner.MiningResult(None, ChainIndex.unsafe(0, 0), 0)
  }

  it should "handle its addresses" in new LockupScriptGenerators {
    val groupConfig      = config.broker
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createBlockHandlersProbe
    val miner = system.actorOf(
      Miner.props(config.network.networkType, config.minerAddresses, blockFlow, allHandlers)
    )

    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .futureValue is config.minerAddresses.map(_.lockupScript)

    val newMinderAddresses =
      AVector.tabulate(groupConfig.groups)(i =>
        Address(networkSetting.networkType, addressGen(GroupIndex.unsafe(i)).sample.get._1)
      )

    miner ! Miner.UpdateAddresses(newMinderAddresses)

    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .futureValue is newMinderAddresses.map(_.lockupScript)

    miner ! Miner.UpdateAddresses(AVector.empty)

    miner
      .ask(Miner.GetAddresses)
      .mapTo[AVector[LockupScript]]
      .futureValue is newMinderAddresses.map(_.lockupScript)
  }
}
