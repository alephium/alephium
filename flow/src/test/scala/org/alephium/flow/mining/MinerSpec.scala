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

package org.alephium.flow.mining

import scala.annotation.tailrec

import akka.testkit.TestActorRef
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.handler.{BlockChainHandler, TestUtils, ViewHandler}
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.validation.InvalidBlockVersion
import org.alephium.protocol.model._
import org.alephium.serde._
import org.alephium.util.{AVector, Duration}

class MinerSpec extends AlephiumFlowActorSpec with ScalaFutures {

  implicit val askTimeout: Timeout = Timeout(Duration.ofSecondsUnsafe(10).asScala)

  trait WorkflowFixture extends FlowFixture with NoIndexModelGeneratorsLike {
    override val configValues =
      Map(("alephium.broker.groups", 1), ("alephium.broker.broker-num", 1))

    val minerAddresses =
      AVector.tabulate(groups0)(g => getGenesisLockupScript(ChainIndex.unsafe(g, 0)))

    val (allHandlers, allHandlersProbes) = TestUtils.createAllHandlersProbe
    val chainIndex                       = chainIndexGen.sample.get
    val blockHandlerProbe                = allHandlersProbes.blockHandlers(chainIndex)

    val miner = TestActorRef[Miner](CpuMiner.props(allHandlers))

    def checkMining(isMining: Boolean) = {
      miner ! Miner.IsMining
      expectMsg(isMining)
    }

    def awaitForBlocks(n: Int) = {
      (0 until n).foreach { _ =>
        miner ! ViewHandler.NewTemplates(
          ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
        )
        val block = blockHandlerProbe.expectMsgType[BlockChainHandler.Validate].block
        block.chainIndex is chainIndex
        miner ! BlockChainHandler.BlockAdded(block.hash)
      }
    }
  }

  it should "initialize FairMiner" in new WorkflowFixture {
    checkMining(false)

    miner ! Miner.Start
    checkMining(true)
    awaitForBlocks(3)

    miner ! Miner.Stop
    checkMining(false)
    miner ! ViewHandler.NewTemplates(
      ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
    )
    blockHandlerProbe.expectNoMessage()

    miner ! Miner.Start
    awaitForBlocks(3)
  }

  it should "continue mining after an invalid block" in new WorkflowFixture {
    miner ! Miner.Start
    for {
      fromShift <- 0 until brokerConfig.groupNumPerBroker
      to        <- 0 until brokerConfig.groups
    } {
      miner.underlyingActor.setRunning(fromShift, to)
    }

    val block = emptyBlock(blockFlow, ChainIndex.unsafe(0, 0))
    miner.underlyingActor.isRunning(0, 0) is true
    miner ! BlockChainHandler.InvalidBlock(block.hash, InvalidBlockVersion)
    eventually(miner.underlyingActor.isRunning(0, 0) is false)
    awaitForBlocks(3)
  }

  it should "mine from both template and data blob" in {
    val chainIndex = ChainIndex.unsafe(0, 1)
    val block      = emptyBlock(blockFlow, chainIndex)
    val template   = Job.from(BlockFlowTemplate.from(block))
    val headerBlob = serialize(block.header).drop(Nonce.byteLength)

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
    val block1Blob = nonce.value ++ headerBlob ++ serialize(block.transactions)
    val block1     = deserialize[Block](block1Blob).rightValue
    addAndCheck(blockFlow, block1)
  }
}
