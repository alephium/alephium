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

package org.alephium.flow.core

import scala.util.Random

import akka.util.ByteString
import org.scalacheck.Gen

import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.{Normal, Reorg}
import org.alephium.flow.validation.BlockValidation
import org.alephium.protocol.{ALPH, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript}
import org.alephium.util._

class FlowUtilsSpec extends AlephiumSpec {
  it should "generate failed tx" in new FlowFixture with NoIndexModelGeneratorsLike {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val groupIndex = chainIndex.from

    forAll(
      assetsToSpendGen(tokensNumGen = Gen.choose(0, 1), scriptGen = p2pkScriptGen(groupIndex))
    ) { assets =>
      val inputs     = assets.map(_.txInput)
      val script     = StatefulScript.alwaysFail
      val unsignedTx = UnsignedTransaction(txScriptOpt = Some(script), inputs, AVector.empty)
      val tx = TransactionTemplate(
        unsignedTx,
        assets.map(asset => SignatureSchema.sign(unsignedTx.id, asset.privateKey)),
        AVector.empty
      )

      val worldState = blockFlow.getBestCachedWorldState(groupIndex).rightValue
      assets.foreach { asset =>
        worldState.addAsset(asset.txInput.outputRef, asset.referredOutput).isRight is true
      }
      val firstInput = assets.head.referredOutput
      val firstOutput = firstInput.copy(
        amount = firstInput.amount.subUnsafe(tx.gasFeeUnsafe),
        additionalData = ByteString.empty
      )
      val bestDeps  = blockFlow.getBestDeps(groupIndex)
      val groupView = blockFlow.getMutableGroupView(groupIndex, bestDeps, worldState).rightValue
      val blockEnv  = blockFlow.getDryrunBlockEnv(unsignedTx.chainIndex).rightValue
      blockFlow.generateFullTx(chainIndex, groupView, blockEnv, tx, script).rightValue is
        Transaction(
          unsignedTx,
          scriptExecutionOk = false,
          AVector.empty,
          (firstOutput +: assets.tail.map(_.referredOutput.copy(additionalData = ByteString.empty)))
            .as[TxOutput],
          tx.inputSignatures,
          tx.scriptSignatures
        )
    }
  }

  it should "check hash order" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield transferOnlyForIntraGroup(blockFlow, ChainIndex.unsafe(i, j))
    newBlocks.foreach { block =>
      addAndCheck(blockFlow, block, 1)
      blockFlow.getWeight(block) isE consensusConfig.minBlockWeight * 1
    }

    newBlocks.map(_.hash).sorted(blockFlow.blockHashOrdering).map(_.bytes) is
      newBlocks.map(_.hash.bytes).sorted(Bytes.byteStringOrdering)

    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(0).hash, newBlocks(0).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(1).hash, newBlocks(0).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(0).hash, newBlocks(1).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(0)(1).hash, newBlocks(1).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(0).hash, newBlocks(2).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(1).hash, newBlocks(2).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(0).hash, newBlocks(3).hash) is true
    blockFlow.blockHashOrdering.lt(blockFlow.genesisBlocks(1)(1).hash, newBlocks(3).hash) is true
  }

  it should "filter double spending txs" in new NoIndexModelGenerators {
    val tx0 = transactionGen(Gen.const(2)).sample.get
    val tx1 = transactionGen(Gen.const(2)).sample.get
    val tx2 = transactionGen(Gen.const(2)).sample.get
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx2)) is AVector(tx0, tx1, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx0, tx2)) is AVector(tx0, tx2)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx1, tx0)) is AVector(tx0, tx1)
    FlowUtils.filterDoubleSpending(AVector(tx0, tx2, tx2)) is AVector(tx0, tx2)
  }

  it should "detect tx conflicts using bestDeps" in new FlowFixture {
    override val configValues =
      Map(
        ("alephium.consensus.uncle-dependency-gap-time", "10 seconds"),
        ("alephium.broker.broker-num", 1)
      )

    val fromGroup      = Random.nextInt(groups0)
    val chainIndex0    = ChainIndex.unsafe(fromGroup, Random.nextInt(groups0))
    val anotherToGroup = (chainIndex0.to.value + 1 + Random.nextInt(groups0 - 1)) % groups0
    val chainIndex1    = ChainIndex.unsafe(fromGroup, anotherToGroup)
    val block0         = transfer(blockFlow, chainIndex0)
    val block1         = transfer(blockFlow, chainIndex1)

    addAndCheck(blockFlow, block0)
    val groupIndex = GroupIndex.unsafe(fromGroup)
    val tx1        = block1.nonCoinbase.head.toTemplate
    blockFlow.isTxConflicted(groupIndex, tx1) is true
    blockFlow.getGrandPool().add(chainIndex1, tx1, TimeStamp.now())

    val miner    = getGenesisLockupScript(chainIndex1)
    val template = blockFlow.prepareBlockFlowUnsafe(chainIndex1, miner)
    template.deps.contains(block0.hash) is false
    template.transactions.init.isEmpty is true
  }

  it should "truncate txs w.r.t. tx number and gas" in new FlowFixture {
    val tx  = transfer(blockFlow, ChainIndex.unsafe(0, 0)).nonCoinbase.head.toTemplate
    val gas = tx.unsigned.gasAmount.value

    val txs = AVector(tx, tx)
    FlowUtils.truncateTxs(txs, 0, GasBox.unsafe(gas * 2)) is txs.take(0)
    FlowUtils.truncateTxs(txs, 1, GasBox.unsafe(gas * 2)) is txs.take(1)
    FlowUtils.truncateTxs(txs, 2, GasBox.unsafe(gas * 2)) is txs.take(2)
    FlowUtils.truncateTxs(txs, 3, GasBox.unsafe(gas * 2)) is txs.take(2)
    FlowUtils.truncateTxs(txs, 0, GasBox.unsafe(gas * 2 - 1)) is txs.take(0)
    FlowUtils.truncateTxs(txs, 1, GasBox.unsafe(gas * 2 - 1)) is txs.take(1)
    FlowUtils.truncateTxs(txs, 2, GasBox.unsafe(gas * 2 - 1)) is txs.take(1)
    FlowUtils.truncateTxs(txs, 3, GasBox.unsafe(gas * 2 - 1)) is txs.take(1)
  }

  it should "prepare block with correct coinbase reward" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val emptyBlock = mineFromMemPool(blockFlow, chainIndex)
    emptyBlock.coinbaseReward is consensusConfig.emission
      .reward(emptyBlock.header)
      .miningReward
    emptyBlock.coinbaseReward is ALPH.alph(30) / 9
    addAndCheck(blockFlow, emptyBlock)

    // generate the block using mineFromMemPool as it uses FlowUtils.prepareBlockFlow
    val transferBlock = {
      val tmpBlock = transfer(blockFlow, chainIndex)
      blockFlow
        .getGrandPool()
        .add(chainIndex, tmpBlock.nonCoinbase.head.toTemplate, TimeStamp.now())
      mineFromMemPool(blockFlow, chainIndex)
    }
    transferBlock.coinbaseReward is consensusConfig.emission
      .reward(transferBlock.header)
      .miningReward
    addAndCheck(blockFlow, transferBlock)
  }

  it should "prepare block template when txs are inter-dependent" in new FlowFixture {
    val blockFlow1 = isolatedBlockFlow()
    val index      = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow1, index)
    val tx0        = block0.nonCoinbase.head
    addAndCheck(blockFlow1, block0)
    val block1 = transfer(blockFlow1, index)
    val tx1    = block1.nonCoinbase.head
    addAndCheck(blockFlow1, block1)

    blockFlow.getGrandPool().add(index, AVector(tx0.toTemplate, tx1.toTemplate), TimeStamp.now())
    val miner = getGenesisLockupScript(index)
    blockFlow.prepareBlockFlowUnsafe(index, miner).transactions.init is AVector(tx0)
  }

  it should "include failed contract tx in block assembly" in new FlowFixture {
    val index = ChainIndex.unsafe(0, 0)
    blockFlow.getGrandPool().add(index, outOfGasTxTemplate, TimeStamp.now())
    val miner    = getGenesisLockupScript(index)
    val template = blockFlow.prepareBlockFlowUnsafe(index, miner)
    template.transactions.length is 2 // it should include the invalid tx
    template.transactions.map(_.id).contains(outOfGasTxTemplate.id) is true

    val validator  = BlockValidation.build(blockFlow)
    val worldState = validator.validateTemplate(index, template, blockFlow).rightValue.get
    worldState.persist().rightValue.contractState.rootHash is
      blockFlow.getBestPersistedWorldState(index.from).rightValue.contractState.rootHash
  }

  it should "reorg" in new FlowFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val mainGroup = GroupIndex.unsafe(0)
    val deps0     = blockFlow.getBestDeps(mainGroup)
    val block0    = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow, block0)
    val block1 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block1)
    val block2 = transfer(blockFlow, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow, block2)
    val deps1 = blockFlow.getBestDeps(mainGroup)

    val blockFlow1 = isolatedBlockFlow()

    val block3 = transfer(blockFlow1, ChainIndex.unsafe(1, 1))
    addAndCheck(blockFlow1, block3)
    val block4 = transfer(blockFlow1, ChainIndex.unsafe(0, 0))
    addAndCheck(blockFlow1, block4)
    val block5 = transfer(blockFlow1, ChainIndex.unsafe(0, 1))
    addAndCheck(blockFlow1, block5)
    val block6 = transfer(blockFlow1, ChainIndex.unsafe(0, 2))
    addAndCheck(blockFlow1, block6)
    val block7 = transfer(blockFlow1, ChainIndex.unsafe(1, 0))
    addAndCheck(blockFlow1, block7)
    val block8 = transfer(blockFlow1, ChainIndex.unsafe(1, 1))
    addAndCheck(blockFlow1, block8)
    val block9 = transfer(blockFlow1, ChainIndex.unsafe(1, 0))
    addAndCheck(blockFlow1, block9)

    addAndCheck(blockFlow, block3)
    addAndCheck(blockFlow, block4)
    addAndCheck(blockFlow, block5)
    addAndCheck(blockFlow, block6)
    addAndCheck(blockFlow, block7)
    addAndCheck(blockFlow, block8)
    addAndCheck(blockFlow, block9)
    val deps2 = blockFlow.getBestDeps(mainGroup)

    blockFlow.calMemPoolChangesUnsafe(mainGroup, deps0, deps1) is
      Normal(
        AVector.from(
          brokerConfig.cliqueGroups
            .filter(_ != mainGroup)
            .map(fromGroup => ChainIndex(fromGroup, mainGroup) -> AVector.empty[Transaction])
        ) ++
          AVector
            .tabulate(groups0)(ChainIndex.unsafe(mainGroup.value, _) -> AVector.empty[Transaction])
            .replace(0, block0.chainIndex -> block0.nonCoinbase)
            .replace(1, block1.chainIndex -> (block1.nonCoinbase ++ block2.nonCoinbase))
      )
    blockFlow.calMemPoolChangesUnsafe(mainGroup, deps1, deps2) is
      Reorg(
        toRemove = AVector.from(
          brokerConfig.cliqueGroups
            .filter(_ != mainGroup)
            .map { fromGroup =>
              ChainIndex(fromGroup, mainGroup) ->
                (if (fromGroup.value == 1) {
                   block7.nonCoinbase
                 } else {
                   AVector.empty[Transaction]
                 })
            }
        ) ++
          AVector
            .tabulate(groups0)(ChainIndex.unsafe(mainGroup.value, _) -> AVector.empty[Transaction])
            .replace(0, block4.chainIndex -> block4.nonCoinbase)
            .replace(1, block5.chainIndex -> block5.nonCoinbase)
            .replace(2, block6.chainIndex -> block6.nonCoinbase),
        toAdd = AVector.from(
          brokerConfig.cliqueGroups
            .filter(_ != mainGroup)
            .map(fromGroup => ChainIndex(fromGroup, mainGroup) -> AVector.empty[Transaction])
        ) ++
          AVector
            .tabulate(groups0)(ChainIndex.unsafe(mainGroup.value, _) -> AVector.empty[Transaction])
            .replace(0, block0.chainIndex -> block0.nonCoinbase)
            .replace(1, block2.chainIndex -> (block2.nonCoinbase ++ block1.nonCoinbase))
      )
  }

  it should "update mempool when needed" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val block0     = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block0)
    val block1    = transfer(blockFlow, chainIndex)
    val tx0       = block0.nonCoinbase.head.toTemplate
    val tx1       = block1.nonCoinbase.head.toTemplate
    val currentTs = TimeStamp.now()

    def test(heightGap: Int, expected: AVector[TransactionTemplate]) = {
      val blockFlow = isolatedBlockFlow()
      val grandPool = blockFlow.getGrandPool()
      val mempool   = blockFlow.getMemPool(chainIndex)
      grandPool.add(chainIndex, tx0, currentTs)
      grandPool.add(chainIndex, tx1, currentTs)
      mempool.contains(tx0.id) is true
      mempool.contains(tx1.id) is true
      mempool.isReady(tx0.id) is true
      mempool.isReady(tx1.id) is false

      val oldDeps = blockFlow.getBestDeps(chainIndex.from)
      addWithoutViewUpdate(blockFlow, block0)
      val newDeps = blockFlow.calBestDepsUnsafe(chainIndex.from)
      blockFlow.updateGrandPoolUnsafe(chainIndex.from, newDeps, oldDeps, heightGap)
      mempool.collectForBlock(chainIndex, Int.MaxValue) is expected
    }

    test(0, AVector(tx0))
    test(1, AVector(tx1))
  }

  it should "assembly block when gas fees are different" in new FlowFixture {
    val chainIndex                  = ChainIndex.unsafe(0)
    val keys                        = Seq.tabulate(10)(_ => chainIndex.from.generateKey)
    val (fromPriKey, fromPubKey, _) = genesisKeys(0)
    keys.foreach { case (_, pubKey) =>
      val block = transfer(blockFlow, fromPriKey, pubKey, ALPH.alph(2))
      addAndCheck(blockFlow, block)
      blockFlow.getBalance(LockupScript.p2pkh(pubKey), Int.MaxValue).rightValue._1 is ALPH.alph(2)
    }

    val txs = keys.zipWithIndex.map { case ((priKey, _), index) =>
      val gasPrice = GasPrice(defaultGasPrice.value + index)
      transferWithGas(
        blockFlow,
        priKey,
        fromPubKey,
        ALPH.oneAlph,
        gasPrice
      ).nonCoinbase.head.toTemplate
    }
    val timestamp = TimeStamp.now()
    Random
      .shuffle(txs)
      .foreach(tx => blockFlow.getGrandPool().add(chainIndex, tx, timestamp))
    val block = blockFlow.prepareBlockFlowUnsafe(chainIndex, LockupScript.p2pkh(fromPubKey))
    block.transactions.init.map(_.toTemplate) is AVector.from(txs.reverse)
  }
}
