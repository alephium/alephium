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

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation.InvalidWorkAmount
import org.alephium.protocol.Generators
import org.alephium.protocol.model.{BlockHash, ChainIndex}
import org.alephium.util.AVector

class AllHandlersSpec extends AlephiumFlowActorSpec {
  override val configValues = Map(
    ("alephium.consensus.num-zeros-at-least-in-hash", 1)
  )

  it should "work for valid block" in {
    val allHandlers =
      AllHandlers.build(system, blockFlow, TestProbe().ref, BlockHash.random.shortHex, storages)

    val dataAddedProbe = TestProbe()
    system.eventStream.subscribe(dataAddedProbe.ref, classOf[ChainHandler.FlowDataAdded])

    val chainIndex = ChainIndex.unsafe(0, 0)
    val blockFlow0 = isolatedBlockFlow()
    val block0     = mineFromMemPool(blockFlow0, chainIndex)
    addAndCheck(blockFlow0, block0)
    val block1 = mineFromMemPool(blockFlow0, chainIndex)
    allHandlers.dependencyHandler ! DependencyHandler.AddFlowData(
      AVector(block0, block1),
      DataOrigin.Local
    )

    expectMsg(BlockChainHandler.BlockAdded(block0.hash))
    dataAddedProbe.expectMsgPF() { case ChainHandler.FlowDataAdded(block, origin, _) =>
      block is block0
      origin is DataOrigin.Local
    }
    expectMsg(BlockChainHandler.BlockAdded(block1.hash))
    dataAddedProbe.expectMsgPF() { case ChainHandler.FlowDataAdded(block, origin, _) =>
      block is block1
      origin is DataOrigin.Local
    }
    blockFlow.contains(block0) isE true
    blockFlow.contains(block1) isE true

    allHandlers.dependencyHandler ! DependencyHandler.GetPendings
    expectMsg(DependencyHandler.Pendings(AVector.empty))
  }

  it should "work for invalid block from peers" in new Generators {
    val allHandlers =
      AllHandlers.build(system, blockFlow, TestProbe().ref, BlockHash.random.shortHex, storages)

    val chainIndex   = ChainIndex.unsafe(0, 0)
    val invalidBlock = invalidNonceBlock(blockFlow, chainIndex)
    allHandlers.dependencyHandler !
      DependencyHandler.AddFlowData(
        AVector(invalidBlock),
        DataOrigin.InterClique(brokerInfoGen.sample.get)
      )

    expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash, InvalidWorkAmount))
    blockFlow.contains(invalidBlock) isE false

    allHandlers.dependencyHandler ! DependencyHandler.GetPendings
    expectMsg(DependencyHandler.Pendings(AVector.empty))
  }

  it should "work for invalid block from local" in new Generators {
    val allHandlers =
      AllHandlers.build(system, blockFlow, TestProbe().ref, BlockHash.random.shortHex, storages)

    val chainIndex   = ChainIndex.unsafe(0, 0)
    val invalidBlock = invalidNonceBlock(blockFlow, chainIndex)
    allHandlers.getBlockHandlerUnsafe(chainIndex) ! BlockChainHandler.Validate(
      invalidBlock,
      testActor,
      DataOrigin.Local
    )
    expectMsg(BlockChainHandler.InvalidBlock(invalidBlock.hash, InvalidWorkAmount))
    blockFlow.contains(invalidBlock) isE false
  }
}
