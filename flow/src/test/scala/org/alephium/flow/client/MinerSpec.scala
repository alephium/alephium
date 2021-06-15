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

import scala.annotation.tailrec

import akka.testkit.TestActorRef
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{BlockChainHandler, TestUtils, ViewHandler}
import org.alephium.flow.model.{DataOrigin, MiningBlob}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util.{AVector, Duration}

class MinerSpec extends AlephiumFlowActorSpec("Miner") with ScalaFutures {

  implicit val askTimeout: Timeout = Timeout(Duration.ofSecondsUnsafe(10).asScala)

  trait WorkflowFixture extends FlowFixture {
    override val configValues =
      Map(("alephium.broker.groups", 1), ("alephium.broker.broker-num", 1))

    val (allHandlers, allHandlersProbes) = TestUtils.createAllHandlersProbe
    val blockHandlerProbe                = allHandlersProbes.blockHandlers(ChainIndex.unsafe(0, 0))

    val miner = TestActorRef[Miner](
      Miner.props(config.network.networkType, config.minerAddresses, blockFlow, allHandlers)
    )

    def checkMining(isMining: Boolean) = {
      miner ! Miner.IsMining
      expectMsg(isMining)
    }

    def awaitForBlocks(n: Int) = {
      (0 until n).foreach { _ =>
        val block = blockHandlerProbe.expectMsgType[BlockChainHandler.Validate].block
        miner ! ViewHandler.ViewUpdated(
          block.chainIndex,
          DataOrigin.Local,
          ViewHandler.prepareTemplates(blockFlow)
        )
      }
    }
  }

  it should "initialize FairMiner" in new WorkflowFixture {
    checkMining(false)

    miner ! Miner.Start
    checkMining(true)
    awaitForBlocks(3)
    val block = blockHandlerProbe.expectMsgType[BlockChainHandler.Validate].block

    miner ! Miner.Stop
    checkMining(false)
    miner ! ViewHandler.ViewUpdated(
      block.chainIndex,
      DataOrigin.Local,
      ViewHandler.prepareTemplates(blockFlow)
    )
    blockHandlerProbe.expectNoMessage()

    miner ! Miner.Start
    awaitForBlocks(3)
  }

  it should "continue mining after an invalid block" in new WorkflowFixture {
    miner.underlyingActor.setRunning(0, 0)
    miner ! Miner.Start
    blockHandlerProbe.expectNoMessage()

    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    miner ! BlockChainHandler.InvalidBlock(block.hash)
    awaitForBlocks(3)
  }

  it should "ignore handled mining result when it's stopped" in {
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createAllHandlersProbe
    val miner = system.actorOf(
      Miner.props(config.network.networkType, config.minerAddresses, blockFlow, allHandlers)
    )

    miner ! Miner.MiningResult(None, ChainIndex.unsafe(0, 0), 0)
  }

  it should "handle its addresses" in new LockupScriptGenerators {
    val groupConfig      = config.broker
    val blockFlow        = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
    val (allHandlers, _) = TestUtils.createAllHandlersProbe
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

  it should "mine from both template and data blob" in {
    val chainIndex = ChainIndex.unsafe(0, 1)
    val block      = emptyBlock(blockFlow, chainIndex)
    val template   = MiningBlob.from(block)
    val headerBlob = serialize(block.header).dropRight(Nonce.byteLength)

    @tailrec
    def iter[T](f: => Option[T]): T = {
      f match {
        case Some(t) => t
        case None    => iter(f)
      }
    }

    val block0 = iter(Miner.mine(chainIndex, template))._1
    addAndCheck(blockFlow, block0)
    val nonce      = iter(Miner.mine(chainIndex, headerBlob, block.target))._1
    val block1Blob = headerBlob ++ nonce.value ++ serialize(block.transactions)
    val block1     = deserialize[Block](block1Blob).rightValue
    addAndCheck(blockFlow, block1)
  }
}
