package org.alephium.flow.core

import scala.annotation.tailrec
import scala.util.Random

import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.core.validation.Validation
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.platform.PlatformConfigFixture
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AVector, U64}

class BlockFlowSpec extends AlephiumFlowSpec { Test =>
  it should "compute correct blockflow height" in {
    val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) isE 0
    }

    checkBalance(blockFlow, config.brokerInfo.groupFrom, genesisBalance)
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block1      = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block1, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block1)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block2      = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block2.header, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block2)
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex4 = ChainIndex.unsafe(0, 0)
      val block4      = mine(blockFlow, chainIndex4)
      addAndCheck(blockFlow, block4, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block4)
      checkBalance(blockFlow, 0, genesisBalance - 3)
    }
  }

  it should "compute cached blocks" in {
    val blockFlow = BlockFlow.fromGenesisUnsafe(storages)
    val newBlocks = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks.foreach { block =>
      val index = block.chainIndex
      if (index.relateTo(GroupIndex.unsafe(0))) {
        addAndCheck(blockFlow, block, 1)
      } else {
        addAndCheck(blockFlow, block.header, 1)
      }
    }

    val cache0 = blockFlow.getHashesForUpdates(GroupIndex.unsafe(0)).toOption.get
    cache0.length is 2
    cache0.contains(newBlocks(0).hash) is false
    cache0.contains(newBlocks(1).hash) is true
  }

  it should "handle contract states" in {
    val input0 =
      s"""
         |contract Foo(mut x: U64) {
         |  fn add(a: U64) -> () {
         |    x = x + a
         |    return
         |  }
         |}
         |""".stripMargin
    val script0      = Compiler.compile(input0).toOption.get
    val initialState = AVector[Val](Val.U64(U64.Zero))

    val chainIndex        = ChainIndex.unsafe(0, 0)
    val blockFlow         = BlockFlow.fromGenesisUnsafe(storages)
    val block0            = mine(blockFlow, chainIndex, outputScriptOption = Some(script0 -> initialState))
    val contractOutputRef = TxOutputRef.unsafe(block0.transactions.head, 0)
    val contractKey       = contractOutputRef.key

    contractOutputRef.isContractRef is true
    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey, initialState)

//    val input1   = ""
//    val script1  = Compiler.compile(input1).toOption.get
//    val newState = AVector[Val](Val.U64(U64.One))
//    val block1   = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
//    addAndCheck(blockFlow, block1, 1)
//    checkState(blockFlow, chainIndex, contractKey, newState)
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks1.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 1)
          blockFlow.getWeight(block) isE config.maxMiningTarget * 1
        } else {
          addAndCheck(blockFlow, block.header, 1)
        }
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks1)
      checkBalance(blockFlow, 0, genesisBalance - 1)
      newBlocks1.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks2 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks2.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 4)
          blockFlow.getChainWeight(block.hash) isE config.maxMiningTarget * 2
        } else {
          addAndCheck(blockFlow, block.header, 4)
        }
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks2)
      checkBalance(blockFlow, 0, genesisBalance - 2)
      newBlocks2.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true

      val newBlocks3 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks3.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 8)
          blockFlow.getChainWeight(block.hash) isE config.maxMiningTarget * 3
        } else {
          addAndCheck(blockFlow, block.header, 8)
        }
      }
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, newBlocks3)
      checkBalance(blockFlow, 0, genesisBalance - 3)
      newBlocks3.map(_.hash).contains(blockFlow.getBestTipUnsafe) is true
    }
  }

  it should "work for 2 user group when there is a fork" in {
    if (config.groups >= 2) {
      val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

      val chainIndex1 = ChainIndex.unsafe(0, 0)
      val block11     = mine(blockFlow, chainIndex1)
      val block12     = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block11, 1)
      addAndCheck(blockFlow, block12, 1)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block11, block12))
      checkBalance(blockFlow, 0, genesisBalance - 1)

      val block13 = mine(blockFlow, chainIndex1)
      addAndCheck(blockFlow, block13, 2)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block13)
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex2 = ChainIndex.unsafe(1, 1)
      val block21     = mine(blockFlow, chainIndex2)
      val block22     = mine(blockFlow, chainIndex2)
      addAndCheck(blockFlow, block21.header, 3)
      addAndCheck(blockFlow, block22.header, 3)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, IndexedSeq(block21, block22))
      checkBalance(blockFlow, 0, genesisBalance - 2)

      val chainIndex3 = ChainIndex.unsafe(0, 1)
      val block3      = mine(blockFlow, chainIndex3)
      addAndCheck(blockFlow, block3, 4)
      checkInBestDeps(GroupIndex.unsafe(0), blockFlow, block3)
      checkBalance(blockFlow, 0, genesisBalance - 3)
    }
  }

  it should "update mempool correctly" in {
    if (config.groups >= 2) {
      val broker = config.brokerInfo
      forAll(Gen.choose(broker.groupFrom, broker.groupUntil - 1)) { mainGroup =>
        val blockFlow = BlockFlow.fromGenesisUnsafe(storages)

        val chainIndex = ChainIndex.unsafe(mainGroup, 0)
        val block11    = mine(blockFlow, chainIndex)
        val block12    = mine(blockFlow, chainIndex)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block11, 1)
        blockFlow.mempools.foreach(_.size is 0)
        addAndCheck(blockFlow, block12, 1)

        val blockAdded = blockFlow.getBestDeps(chainIndex.from).getOutDep(chainIndex.to)
        if (blockAdded equals block12.hash) {
          blockAdded is block12.hash
          blockFlow.getPool(chainIndex).size is block11.transactions.length - 1
          val template = blockFlow.prepareBlockFlow(chainIndex).toOption.get
          template.transactions.length is block11.transactions.length - 1
        }
      }
    }
  }

  it should "reload blockflow properly from storage" in {
    val blockFlow0 = BlockFlow.fromGenesisUnsafe(storages)

    val newBlocks1 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow0, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks1.foreach { block =>
      val index = block.chainIndex
      if (index.relateTo(GroupIndex.unsafe(0))) {
        addAndCheck(blockFlow0, block, 1)
      } else {
        addAndCheck(blockFlow0, block.header, 1)
      }
    }
    newBlocks1.map(_.hash).diff(blockFlow0.getAllTips.toArray).isEmpty is true

    val blockFlow1 = BlockFlow.fromStorageUnsafe(storages)
    newBlocks1.map(_.hash).diff(blockFlow1.getAllTips.toArray).isEmpty is true

    val newBlocks2 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow1, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks2.foreach { block =>
      val index = block.chainIndex
      if (index.relateTo(GroupIndex.unsafe(0))) {
        addAndCheck(blockFlow1, block, 4)
        blockFlow1.getChainWeight(block.hash) isE config.maxMiningTarget * 2
      } else {
        addAndCheck(blockFlow1, block.header, 4)
      }
    }
    checkInBestDeps(GroupIndex.unsafe(0), blockFlow1, newBlocks2)
    checkBalance(blockFlow1, 0, genesisBalance - 2)
    newBlocks2.map(_.hash).contains(blockFlow1.getBestTipUnsafe) is true
  }

  behavior of "Balance"

  it should "transfer token for inside a same group" in {
    val blockFlow = BlockFlow.fromGenesisUnsafe(storages)
    val testGroup = Random.nextInt(config.groupNumPerBroker) + config.brokerInfo.groupFrom
    val block     = mine(blockFlow, ChainIndex.unsafe(testGroup, testGroup), onlyTxForIntra = true)
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow, block, 1)

    val pubScript =
      block.nonCoinbase.head.unsigned.fixedOutputs.head.asInstanceOf[AssetOutput].lockupScript
    checkBalance(blockFlow, pubScript, 1)
    checkBalance(blockFlow, testGroup, genesisBalance - 1)
  }

  it should "transfer token for inter-group transactions" in {
    import config.brokerInfo

    val anotherBroker = (brokerInfo.id + 1 + Random.nextInt(config.brokerNum - 1)) % config.brokerNum
    val newConfigFixture = new PlatformConfigFixture {
      override val configValues = Map(
        ("alephium.broker.brokerId", anotherBroker)
      )

      override lazy val genesisBalances = Test.genesisBalances
    }
    Test.genesisBalance is newConfigFixture.genesisBalance

    val anotherConfig   = newConfigFixture.config
    val anotherStorages = StoragesFixture.buildStorages(anotherConfig)
    config.genesisBlocks is anotherConfig.genesisBlocks
    val blockFlow0 = BlockFlow.fromGenesisUnsafe(storages)(config)
    val blockFlow1 = BlockFlow.fromGenesisUnsafe(anotherStorages)(anotherConfig)

    val fromGroup = Random.nextInt(config.groupNumPerBroker) + brokerInfo.groupFrom
    val toGroup   = Random.nextInt(config.groupNumPerBroker) + anotherConfig.brokerInfo.groupFrom
    val block     = mine(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    block.nonCoinbase.nonEmpty is true

    addAndCheck(blockFlow0, block, 1)
    checkBalance(blockFlow0, fromGroup, genesisBalance - 1)

    addAndCheck(blockFlow1, block, 1)
    val pubScript =
      block.nonCoinbase.head.unsigned.fixedOutputs.head.asInstanceOf[AssetOutput].lockupScript
    checkBalance(blockFlow1, pubScript, 1)
  }

  def mine(blockFlow: BlockFlow,
           chainIndex: ChainIndex,
           onlyTxForIntra: Boolean                                     = false,
           outputScriptOption: Option[(StatelessScript, AVector[Val])] = None,
           txScriptOption: Option[StatelessScript]                     = None): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from).deps
    val height           = blockFlow.getHashChain(chainIndex).maxHeight.toOption.get
    val (_, toPublicKey) = chainIndex.to.generateKey
    val coinbaseTx       = Transaction.coinbase(toPublicKey, height, Hash.generate.bytes)
    val transactions = {
      if (config.brokerInfo.contains(chainIndex.from) && (chainIndex.isIntraGroup || !onlyTxForIntra)) {
        val mainGroup                  = chainIndex.from
        val (privateKey, publicKey, _) = genesisBalances(mainGroup.value)
        val fromLockupScript           = LockupScript.p2pkh(publicKey)
        val unlockScript               = UnlockScript.p2pkh(publicKey)
        val balances                   = blockFlow.getUtxos(fromLockupScript).toOption.get
        val total                      = balances.fold(U64.Zero)(_ addUnsafe _._2.amount)
        val (_, toPublicKey)           = chainIndex.to.generateKey
        val toLockupScript             = LockupScript.p2pkh(toPublicKey)
        val inputs                     = balances.map(_._1).map(TxInput(_, unlockScript))

        val output0 = outputScriptOption match {
          case Some((script, _)) => TxOutput.contract(1, height, toLockupScript, script)
          case None              => TxOutput.asset(1, height, toLockupScript)
        }
        val output1 = TxOutput.asset(total - 1, height, fromLockupScript)
        val outputs = AVector[TxOutput](output0, output1)
        val unsignedTx = outputScriptOption match {
          case Some((_, state)) =>
            UnsignedTransaction(txScriptOption, inputs, outputs, AVector(state))
          case None =>
            UnsignedTransaction(txScriptOption, inputs, outputs, AVector.empty)
        }
        val transferTx = Transaction.from(unsignedTx, privateKey)
        AVector(transferTx, coinbaseTx)
      } else AVector(coinbaseTx)
    }

    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.from(deps, transactions, config.maxMiningTarget, nonce)
      if (Validation.validateMined(block, chainIndex)) block else iter(nonce + 1)
    }

    iter(0)
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    blockFlow.add(block).isRight is true
    blockFlow.getWeight(block) isE config.maxMiningTarget * weightRatio
  }

  def checkInBestDeps(groupIndex: GroupIndex, blockFlow: BlockFlow, block: Block): Assertion = {
    blockFlow.getBestDeps(groupIndex).deps.contains(block.hash) is true
  }

  def checkInBestDeps(groupIndex: GroupIndex,
                      blockFlow: BlockFlow,
                      blocks: IndexedSeq[Block]): Assertion = {
    val bestDeps = blockFlow.getBestDeps(groupIndex).deps
    blocks.exists { block =>
      bestDeps.contains(block.hash)
    } is true
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    blockFlow.add(header).isRight is true
    blockFlow.getWeight(header) isE config.maxMiningTarget * weightFactor
  }

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: U64): Assertion = {
    val address   = genesisBalances(groupIndex)._2
    val pubScript = LockupScript.p2pkh(address)
    blockFlow
      .getUtxos(pubScript)
      .toOption
      .get
      .sumBy(_._2.amount.v) is expected.v
  }

  def checkBalance(blockFlow: BlockFlow, pubScript: LockupScript, expected: U64): Assertion = {
    blockFlow.getUtxos(pubScript).toOption.get.sumBy(_._2.amount.v) is expected.v
  }

  def checkState(blockFlow: BlockFlow,
                 chainIndex: ChainIndex,
                 key: Hash,
                 expected: AVector[Val]): Assertion = {
    blockFlow.getBestTrie(chainIndex).toOption.get.getContractState(key) isE expected
  }

  def show(blockFlow: BlockFlow): String = {
    val tips = blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeightUnsafe(tip)
        val header = blockFlow.getBlockHeaderUnsafe(tip)
        val index  = header.chainIndex
        val deps   = header.blockDeps.map(_.shortHex).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: ${tip.shortHex}, deps: $deps"
      }
      .mkString("", "\n", "\n")
    val bestDeps = (config.brokerInfo.groupFrom until config.brokerInfo.groupUntil)
      .map { group =>
        val bestDeps    = blockFlow.getBestDeps(GroupIndex.unsafe(group))
        val bestDepsStr = bestDeps.deps.map(_.shortHex).mkString("-")
        s"group $group, bestDeps: $bestDepsStr"
      }
      .mkString("", "\n", "\n")
    tips ++ bestDeps
  }

  def getBalance(blockFlow: BlockFlow, address: ED25519PublicKey): U64 = {
    val lockupScript = LockupScript.p2pkh(address)
    config.brokerInfo.contains(lockupScript.groupIndex) is true
    val query = blockFlow.getUtxos(lockupScript)
    query.toOption.get.sumBy(_._2.amount.v)
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      s"${txOutput.scriptHint}:${txOutput.amount}"
    }

    val address   = genesisBalances(config.brokerInfo.id)._2
    val pubScript = LockupScript.p2pkh(address)
    val txOutputs = blockFlow.getUtxos(pubScript).toOption.get.map(_._2)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }
}
