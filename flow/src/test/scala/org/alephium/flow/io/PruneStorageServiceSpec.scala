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

package org.alephium.flow.io

import scala.util.Random

import org.alephium.flow.FlowFixture
import org.alephium.flow.core.BlockFlow
import org.alephium.io.RocksDBKeyValueStorage
import org.alephium.io.SparseMerkleTrie.Node
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Address, Block, ChainIndex, ContractId}
import org.alephium.protocol.vm.TokenIssuance
import org.alephium.util.{AlephiumSpec, AVector}

class PruneStorageServiceSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    override val configValues = Map(
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    lazy val chainIndex     = ChainIndex.unsafe(0, 0)
    val genesisLockup       = getGenesisLockupScript(chainIndex)
    val genesisAddress      = Address.Asset(genesisLockup)
    val blockChain          = blockFlow.getBlockChain(chainIndex)
    val pruneStorageService = new PruneStorageService(storages)(blockFlow, groupConfig)
    val trieStorage =
      storages.worldStateStorage.trieStorage.asInstanceOf[RocksDBKeyValueStorage[Hash, Node]]

    val tokenContract =
      s"""
         |Contract Token() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw(address: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
         |  }
         |}
         |""".stripMargin

    def callContract(contractId: ContractId) = {
      callTxScript(
        s"""
           |TxScript Main {
           |  let token = Token(#${contractId.toHexString})
           |  token.withdraw(@${genesisAddress.toBase58}, 1024)
           |}
           |
           |$tokenContract
           |""".stripMargin
      )
    }

    def addOneBlock(chainIndex: ChainIndex, blockFlow: BlockFlow): Block = {
      val block = Random.nextBoolean() match {
        case true  => transfer(blockFlow, chainIndex)
        case false => emptyBlock(blockFlow, chainIndex)
      }
      addAndCheck(blockFlow, block)
      block
    }

    def pruneAndVerify(): Set[Hash] = {
      val keysBeforePruning: Set[Hash] = getAllKeysInTrie().toSet
      pruneStorageService.prune()
      val keysAfterPruning: Set[Hash] = getAllKeysInTrie().toSet
      keysBeforePruning.size > keysAfterPruning.size is true
      keysAfterPruning
    }

    def getAllKeysInTrie(): Seq[Hash] = {
      var allKeys: Seq[Hash] = Seq.empty[Hash]
      trieStorage.iterateRawE((k, _) => {
        allKeys = allKeys :+ Hash.unsafe(k)
        Right(())
      })
      allKeys
    }
  }

  it should "verify that applyBlock works" in new Fixture {
    addOneBlock(chainIndex, blockFlow)
    addOneBlock(chainIndex, blockFlow)

    val (contractId, _) = createContract(
      tokenContract,
      AVector.empty,
      AVector.empty,
      tokenIssuanceInfo = Some(TokenIssuance.Info(1024)),
      chainIndex = chainIndex
    )

    addOneBlock(chainIndex, blockFlow)
    val allKeysAtBlock4 = getAllKeysInTrie()

    val block5 = callContract(contractId)

    val restOfBlocks = (0 until 95).map { _ => addOneBlock(chainIndex, blockFlow) }

    val allKeysFinal: Set[Hash] = getAllKeysInTrie().toSet

    blockFlow.getMaxHeightByWeight(chainIndex).rightValue is 100

    val keysAfterApplyingRestOfBlocks = (block5 +: restOfBlocks)
      .foldLeft(allKeysAtBlock4) { (acc, block) =>
        val result = pruneStorageService.applyBlock(block.hash).rightValue
        acc ++ result
      }
      .toSet

    allKeysFinal is keysAfterApplyingRestOfBlocks
  }

  it should "continue to work after pruning" in new Fixture {
    groupConfig.cliqueGroupIndexes.foreach { groupIndex =>
      val ci = ChainIndex(groupIndex, groupIndex)
      (0 until 200).map { _ => addOneBlock(ci, blockFlow) }

      blockFlow.getMaxHeightByWeight(ci).rightValue is 200
    }

    val (contractId, _) = createContract(
      tokenContract,
      AVector.empty,
      AVector.empty,
      tokenIssuanceInfo = Some(TokenIssuance.Info(1024)),
      chainIndex = chainIndex
    )

    val keysAfterPruning = pruneAndVerify()

    (0 until 200).map { _ => addOneBlock(chainIndex, blockFlow) }

    callContract(contractId)

    val keysAfterReSync: Set[Hash] = getAllKeysInTrie().toSet
    keysAfterReSync.size > keysAfterPruning.size is true
  }

  it should "not work if there are less than 128 blocks" in new Fixture {
    groupConfig.cliqueGroupIndexes.foreach { groupIndex =>
      val ci = ChainIndex(groupIndex, groupIndex)
      (0 until 126).map { _ => addOneBlock(ci, blockFlow) }
      blockFlow.getMaxHeightByWeight(ci).rightValue is 126
    }

    assertThrows[AssertionError](pruneStorageService.prune())

    groupConfig.cliqueGroupIndexes.take(groupConfig.groups - 1).foreach { groupIndex =>
      val ci = ChainIndex(groupIndex, groupIndex)
      (0 until 10).map { _ => addOneBlock(ci, blockFlow) }
      blockFlow.getMaxHeightByWeight(ci).rightValue is 136
    }

    assertThrows[AssertionError](pruneStorageService.prune())

    groupConfig.cliqueGroupIndexes.drop(groupConfig.groups - 1).foreach { groupIndex =>
      val ci = ChainIndex(groupIndex, groupIndex)
      (0 until 10).map { _ => addOneBlock(ci, blockFlow) }
      blockFlow.getMaxHeightByWeight(ci).rightValue is 136
    }

    pruneAndVerify()
  }
}
