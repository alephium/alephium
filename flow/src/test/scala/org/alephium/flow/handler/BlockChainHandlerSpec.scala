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

import java.net.InetSocketAddress

import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{InterCliqueManager, IntraCliqueManager}
import org.alephium.flow.network.broker.MisbehaviorManager
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.{InvalidBlockHeight, InvalidBlockVersion, InvalidTxsMerkleRoot}
import org.alephium.protocol.message.{Message, NewBlock, NewHeader}
import org.alephium.protocol.model.{Block, BlockHeader, BrokerInfo, ChainIndex, CliqueId}
import org.alephium.serde.serialize
import org.alephium.util.ActorRefT

class BlockChainHandlerSpec extends AlephiumFlowActorSpec {
  trait Fixture extends FlowFixture {
    val brokerInfo = BrokerInfo.unsafe(CliqueId.zero, 0, 1, new InetSocketAddress("127.0.0.1", 0))
    val brokerHandler       = TestProbe()
    val dataOrigin          = DataOrigin.InterClique(brokerInfo): DataOrigin
    val interCliqueListener = TestProbe()
    val intraCliqueListener = TestProbe()
    val maxForkDepth        = 5
    lazy val chainIndex     = ChainIndex.unsafe(0, 0)
    lazy val blockChainHandler =
      TestActorRef[BlockChainHandler](
        BlockChainHandler.props(blockFlow, chainIndex, ActorRefT(TestProbe().ref), maxForkDepth)
      )

    system.eventStream.subscribe(
      interCliqueListener.ref,
      classOf[InterCliqueManager.BroadCastBlock]
    )
    system.eventStream.subscribe(
      intraCliqueListener.ref,
      classOf[IntraCliqueManager.BroadCastBlock]
    )

    def validateBlock(block: Block): Unit = {
      blockChainHandler ! BlockChainHandler.Validate(
        block,
        ActorRefT(brokerHandler.ref),
        dataOrigin
      )
    }
    def blockMsg(block: Block): ByteString         = Message.serialize(NewBlock(block))
    def headerMsg(header: BlockHeader): ByteString = Message.serialize(NewHeader(header))
  }

  it should "not broadcast block if the block comes from other broker groups" in new Fixture { F =>
    val fixture = new AlephiumConfigFixture {
      override val configValues = Map(
        ("alephium.broker.broker-id", 1)
      )
      override lazy val genesisKeys = F.genesisKeys
    }
    val blockFlow1 = BlockFlow.fromGenesisUnsafe(fixture.config, storages)
    blockFlow.brokerConfig.brokerId is 0
    blockFlow1.brokerConfig.brokerId is 1

    val block = emptyBlock(blockFlow1, ChainIndex.unsafe(1, 0))
    validateBlock(block)
    interCliqueListener.expectNoMessage()
    intraCliqueListener.expectNoMessage()
    brokerHandler.expectMsg(BlockChainHandler.BlockAdded(block.hash))
  }

  it should "broadcast block only if node synced" in new Fixture {
    val block1 = emptyBlock(blockFlow, chainIndex)

    validateBlock(block1)
    interCliqueListener.expectNoMessage()
    val intraCliqueMessage1 = IntraCliqueManager.BroadCastBlock(
      block1,
      blockMsg(block1),
      headerMsg(block1.header),
      dataOrigin
    )
    intraCliqueListener.expectMsg(intraCliqueMessage1)
    brokerHandler.expectMsg(BlockChainHandler.BlockAdded(block1.hash))

    val block2 = emptyBlock(blockFlow, chainIndex)
    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(block2)
    val interCliqueMessage = InterCliqueManager.BroadCastBlock(
      block2,
      blockMsg(block2),
      dataOrigin
    )
    interCliqueListener.expectMsg(interCliqueMessage)
    val intraCliqueMessage2 = IntraCliqueManager.BroadCastBlock(
      block2,
      blockMsg(block2),
      headerMsg(block2.header),
      dataOrigin
    )
    intraCliqueListener.expectMsg(intraCliqueMessage2)
    brokerHandler.expectMsg(BlockChainHandler.BlockAdded(block2.hash))
  }

  it should "not broadcast block only if there is only one broker in clique" in new Fixture {
    override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))

    val block = emptyBlock(blockFlow, chainIndex)
    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(block)
    val interCliqueMessage = InterCliqueManager.BroadCastBlock(
      block,
      blockMsg(block),
      dataOrigin
    )
    interCliqueListener.expectMsg(interCliqueMessage)
    intraCliqueListener.expectNoMessage()
    brokerHandler.expectMsg(BlockChainHandler.BlockAdded(block.hash))
  }

  it should "broadcast block if the block is valid" in new Fixture {
    val block = emptyBlock(blockFlow, chainIndex)

    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(block)
    val interCliqueMessage = InterCliqueManager.BroadCastBlock(
      block,
      blockMsg(block),
      dataOrigin
    )
    interCliqueListener.expectMsg(interCliqueMessage)
    val intraCliqueMessage = IntraCliqueManager.BroadCastBlock(
      block,
      blockMsg(block),
      headerMsg(block.header),
      dataOrigin
    )
    intraCliqueListener.expectMsg(intraCliqueMessage)
    brokerHandler.expectMsg(BlockChainHandler.BlockAdded(block.hash))
  }

  it should "broadcast block if the block header is valid" in new Fixture {
    // block header is valid, but block is invalid
    val block        = transfer(blockFlow, chainIndex)
    val invalidBlock = Block(block.header, block.nonCoinbase ++ block.transactions)

    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(invalidBlock)
    val interCliqueMessage = InterCliqueManager.BroadCastBlock(
      invalidBlock,
      blockMsg(invalidBlock),
      dataOrigin
    )
    blockFlow.getHeaderVerifiedBlockBytes(invalidBlock.hash) isE serialize(invalidBlock)
    blockFlow.getBlock(invalidBlock.hash).isLeft is true
    interCliqueListener.expectMsg(interCliqueMessage)
    intraCliqueListener.expectNoMessage()
    brokerHandler.expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash, InvalidTxsMerkleRoot))
  }

  trait InvalidBlockFixture extends Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val block         = emptyBlock(blockFlow, chainIndex)
    val invalidHeader = block.header.copy(version = 4.toByte)
    val invalidBlock  = block.copy(header = invalidHeader)
  }

  it should "not broadcast block if the block header is invalid and the block is not mined locally" in new InvalidBlockFixture {
    override val dataOrigin = DataOrigin.InterClique(brokerInfo)

    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(invalidBlock)

    brokerHandler.expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash, InvalidBlockVersion))
    interCliqueListener.expectNoMessage()
    intraCliqueListener.expectNoMessage()
  }

  it should "broadcast block if the block header is invalid and the block is mined locally" in new InvalidBlockFixture {
    override val dataOrigin = DataOrigin.Local

    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    blockChainHandler ! BlockChainHandler.ValidateMinedBlock(
      invalidBlock.hash,
      serialize(invalidBlock),
      ActorRefT(brokerHandler.ref)
    )

    brokerHandler.expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash, InvalidBlockVersion))
    interCliqueListener.expectMsg(
      InterCliqueManager.BroadCastBlock(
        chainIndex,
        invalidBlock.hash,
        blockMsg(invalidBlock),
        dataOrigin
      )
    )
    intraCliqueListener.expectNoMessage()
  }

  it should "publish misbehavior when receiving deep forked blocks from remote" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val invalidForkedBlock = emptyBlock(blockFlow, chainIndex)
    val listener           = TestProbe()
    val blockChain         = blockFlow.getBlockChain(chainIndex)

    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex))
    blockChain.maxHeightByWeightUnsafe is 2
    val validForkedBlock = emptyBlock(blockFlow, chainIndex)
    (0 until maxForkDepth).foreach(_ => addAndCheck(blockFlow, emptyBlock(blockFlow, chainIndex)))
    blockChain.maxHeightByWeightUnsafe is (2 + maxForkDepth)

    val brokerProbe = TestProbe()
    val broker      = ActorRefT[ChainHandler.Event](brokerProbe.ref)
    blockChainHandler ! BlockChainHandler.Validate(validForkedBlock, broker, dataOrigin)
    eventually {
      blockChain.contains(validForkedBlock) isE true
      brokerProbe.expectMsg(BlockChainHandler.BlockAdded(validForkedBlock.hash))
    }

    system.eventStream.subscribe(listener.ref, classOf[MisbehaviorManager.Misbehavior])
    blockChainHandler ! BlockChainHandler.Validate(invalidForkedBlock, broker, dataOrigin)
    eventually {
      brokerProbe.expectMsg(
        BlockChainHandler.InvalidBlock(invalidForkedBlock.hash, InvalidBlockHeight)
      )
      listener.expectMsg(MisbehaviorManager.DeepForkBlock(brokerInfo.address))
    }

    blockChainHandler ! BlockChainHandler.Validate(invalidForkedBlock, broker, DataOrigin.Local)
    eventually {
      brokerProbe.expectMsg(
        BlockChainHandler.InvalidBlock(invalidForkedBlock.hash, InvalidBlockHeight)
      )
      listener.expectNoMessage()
    }
  }
}
