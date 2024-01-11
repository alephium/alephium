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
import org.alephium.protocol.model.Address
import org.alephium.protocol.model.Block
import org.alephium.protocol.model.ChainIndex
import org.alephium.protocol.vm.TokenIssuance
import org.alephium.util.{AlephiumSpec, AVector}

class PruneStorageServiceSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    override val configValues = Map(
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    val blockChain      = blockFlow.getBlockChain(chainIndex)

    def addOneBlock(chainIndex: ChainIndex, blockFlow: BlockFlow): Block = {
      val block = Random.nextBoolean() match {
        case true  => transfer(blockFlow, chainIndex)
        case false => emptyBlock(blockFlow, chainIndex)
      }
      addAndCheck(blockFlow, block)
      block
    }

    val trieStorage =
      storages.worldStateStorage.trieStorage.asInstanceOf[RocksDBKeyValueStorage[Hash, Node]]
  }

  it should "verify that applyBlock works" in new Fixture {
    val genesisLockup     = getGenesisLockupScript(chainIndex)
    val genesisAddress    = Address.Asset(genesisLockup)
    val pruneStateService = new PruneStorageService(storages)(blockFlow, groupConfig)

    addOneBlock(chainIndex, blockFlow)
    addOneBlock(chainIndex, blockFlow)

    val tokenContract =
      s"""
         |Contract Token() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw(address: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
         |  }
         |}
         |""".stripMargin

    val (contractId, _) = createContract(
      tokenContract,
      AVector.empty,
      AVector.empty,
      tokenIssuanceInfo = Some(TokenIssuance.Info(1024)),
      chainIndex = chainIndex
    )

    val block4          = addOneBlock(chainIndex, blockFlow)
    val allKeysAtBlock4 = getAllKeys(trieStorage)

    val block5 = callTxScript(
      s"""
         |TxScript Main {
         |  let token = Token(#${contractId.toHexString})
         |  token.withdraw(@${genesisAddress.toBase58}, 1024)
         |}
         |
         |$tokenContract
         |""".stripMargin
    )

    val restOfBlocks = (0 until 95).map { _ => addOneBlock(chainIndex, blockFlow) }

    val allKeysFinal: Set[Hash] = getAllKeys(trieStorage).toSet

    blockFlow.getMaxHeight(chainIndex).rightValue is 100

    val keysAfterApplyingRestOfBlocks = (block5 +: restOfBlocks)
      .foldLeft(allKeysAtBlock4) { (acc, block) =>
        val result = pruneStateService.applyBlock(block4.hash, block.hash).rightValue
        acc ++ result
      }
      .toSet

    allKeysFinal is keysAfterApplyingRestOfBlocks
  }

  it should "continue to work after pruning" in new Fixture {
    val pruneStateService = new PruneStorageService(storages)(blockFlow, groupConfig)

    groupConfig.cliqueGroupIndexes.foreach { groupIndex =>
      val ci = ChainIndex(groupIndex, groupIndex)
      (0 until 200).map { _ => addOneBlock(ci, blockFlow) }

      blockFlow.getMaxHeight(ci).rightValue is 200
    }

    val keysBeforePruning: Set[Hash] = getAllKeys(trieStorage).toSet
    pruneStateService.prune()
    val keysAfterPruning: Set[Hash] = getAllKeys(trieStorage).toSet

    keysBeforePruning.size > keysAfterPruning.size is true

    (0 until 200).map { _ => addOneBlock(chainIndex, blockFlow) }

    val keysAfterReSync: Set[Hash] = getAllKeys(trieStorage).toSet
    keysAfterReSync.size > keysAfterPruning.size is true
  }

  it should "not work if there are less than 128 blocks" in new Fixture {
    val pruneStateService = new PruneStorageService(storages)(blockFlow, groupConfig)

    (0 until 100).map { _ => addOneBlock(chainIndex, blockFlow) }

    assertThrows[AssertionError](pruneStateService.prune())
  }

  private def getAllKeys(trieStorage: RocksDBKeyValueStorage[Hash, Node]): Seq[Hash] = {
    var allKeys: Seq[Hash] = Seq.empty[Hash]
    trieStorage.iterateRawE((k, _) => {
      allKeys = allKeys :+ Hash.unsafe(k)
      Right(())
    })
    allKeys
  }
}
