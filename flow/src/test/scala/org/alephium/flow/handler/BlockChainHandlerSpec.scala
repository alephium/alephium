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

import akka.testkit.{TestActorRef, TestProbe}
import akka.util.ByteString

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.{InterCliqueManager, IntraCliqueManager}
import org.alephium.protocol.Hash
import org.alephium.protocol.message.{Message, NewBlock, NewHeader}
import org.alephium.protocol.model.{Block, BlockHeader, ChainIndex, Transaction}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{ActorRefT, AVector, TimeStamp}

class BlockChainHandlerSpec extends AlephiumFlowActorSpec {
  trait Fixture extends FlowFixture {
    val brokerHandler       = TestProbe()
    val dataOrigin          = DataOrigin.Local
    val interCliqueListener = TestProbe()
    val intraCliqueListener = TestProbe()
    lazy val chainIndex     = ChainIndex.unsafe(0, 0)
    lazy val blockChainHandler =
      TestActorRef[BlockChainHandler](
        BlockChainHandler.props(blockFlow, chainIndex, ActorRefT(TestProbe().ref))
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

  it should "not broadcast block if the block comes from other broker groups" in new Fixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 2),
      ("alephium.broker.broker-id", 0)
    )

    def createBlock(chainIndex: ChainIndex): Block = {
      assume(brokerConfig.contains(chainIndex.to) && !brokerConfig.contains(chainIndex.from))
      val mainGroup = chainIndex.from
      val inDeps =
        blockFlow.initialGenesisHashes.filter(hash => ChainIndex.from(hash).from != mainGroup)
      val outDeps      = blockFlow.genesisHashes(mainGroup.value)
      val blockTs      = TimeStamp.now()
      val (_, pubKey)  = mainGroup.generateKey
      val lockupScript = LockupScript.p2pkh(pubKey)
      val coinbaseTx = Transaction.coinbase(
        chainIndex,
        AVector.empty[Transaction],
        lockupScript,
        consensusConfig.maxMiningTarget,
        blockTs
      )
      val txs         = AVector(coinbaseTx)
      val txsHash     = Block.calTxsHash(txs)
      val blockHeader = mineHeader(chainIndex, inDeps ++ outDeps, Hash.generate, txsHash, blockTs)
      Block(blockHeader, txs)
    }

    val block = createBlock(ChainIndex.unsafe(1, 0))
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
      DataOrigin.Local
    )
    interCliqueListener.expectMsg(interCliqueMessage)
    val intraCliqueMessage = IntraCliqueManager.BroadCastBlock(
      block,
      blockMsg(block),
      headerMsg(block.header),
      DataOrigin.Local
    )
    intraCliqueListener.expectMsg(intraCliqueMessage)
    brokerHandler.expectMsg(BlockChainHandler.BlockAdded(block.hash))
  }

  it should "broadcast block if the block header is valid" in new Fixture {
    // block header is valid, but block is invalid
    val block        = transfer(blockFlow, chainIndex)
    val invalidBlock = Block(block.header, block.transactions ++ block.transactions)

    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(invalidBlock)
    val interCliqueMessage = InterCliqueManager.BroadCastBlock(
      invalidBlock,
      blockMsg(invalidBlock),
      DataOrigin.Local
    )
    interCliqueListener.expectMsg(interCliqueMessage)
    intraCliqueListener.expectNoMessage()
    brokerHandler.expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash))
  }

  it should "not broadcast block if the block header is invalid" in new Fixture {
    // block header is invalid
    val block         = emptyBlock(blockFlow, chainIndex)
    val invalidHeader = block.header.copy(version = 4.toByte)
    val invalidBlock  = block.copy(header = invalidHeader)

    blockChainHandler ! InterCliqueManager.SyncedResult(true)
    validateBlock(invalidBlock)
    interCliqueListener.expectNoMessage()
    intraCliqueListener.expectNoMessage()
    brokerHandler.expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash))
  }
}
