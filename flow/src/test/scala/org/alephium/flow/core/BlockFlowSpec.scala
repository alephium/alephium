package org.alephium.flow.core

import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto.ED25519PublicKey
import org.alephium.flow.AlephiumFlowSpec
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AVector, Random, U64}

class BlockFlowSpec extends AlephiumFlowSpec { Test =>
  it should "compute correct blockflow height" in {
    val blockFlow = genesisBlockFlow()

    config.genesisBlocks.flatMap(identity).foreach { block =>
      blockFlow.getWeight(block.hash) isE 0
    }

    checkBalance(blockFlow, brokerConfig.groupFrom, genesisBalance)
  }

  it should "work for at least 2 user group when adding blocks sequentially" in {
    if (brokerConfig.groups >= 2) {
      val blockFlow = genesisBlockFlow()

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
    val blockFlow = genesisBlockFlow()
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
         |TxContract Foo(mut x: U64) {
         |  fn add(a: U64) -> () {
         |    x = x + a
         |    return
         |  }
         |}
         |""".stripMargin
    val script0      = Compiler.compileContract(input0).toOption.get
    val initialState = AVector[Val](Val.U64(U64.Zero))

    val chainIndex         = ChainIndex.unsafe(0, 0)
    val blockFlow          = genesisBlockFlow()
    val block0             = mine(blockFlow, chainIndex, outputScriptOption = Some(script0 -> initialState))
    val contractOutputRef0 = TxOutputRef.unsafe(block0.transactions.head, 0)
    val contractKey0       = contractOutputRef0.key

    contractOutputRef0 is a[ContractOutputRef]
    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState)

    val input1 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  fn add(a: U64) -> () {
         |    x = x + a
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  fn call() -> () {
         |    let foo = Foo(@${contractKey0.toHexString})
         |    foo.add(1)
         |    return
         |  }
         |}
         |""".stripMargin
    val script1   = Compiler.compileTxScript(input1, 1).toOption.get
    val newState1 = AVector[Val](Val.U64(U64.One))
    val block1    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractKey0, newState1)

    val newState2 = AVector[Val](Val.U64(U64.Two))
    val block2    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractKey0, newState2)
  }

  it should "work for at least 2 user group when adding blocks in parallel" in {
    if (brokerConfig.groups >= 2) {
      val blockFlow = genesisBlockFlow()

      val newBlocks1 = for {
        i <- 0 to 1
        j <- 0 to 1
      } yield mine(blockFlow, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
      newBlocks1.foreach { block =>
        val index = block.chainIndex
        if (index.relateTo(GroupIndex.unsafe(0))) {
          addAndCheck(blockFlow, block, 1)
          blockFlow.getWeight(block) isE consensusConfig.maxMiningTarget * 1
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
          blockFlow.getChainWeight(block.hash) isE consensusConfig.maxMiningTarget * 2
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
          blockFlow.getChainWeight(block.hash) isE consensusConfig.maxMiningTarget * 3
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
    if (brokerConfig.groups >= 2) {
      val blockFlow = genesisBlockFlow()

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
    if (brokerConfig.groups >= 2) {
      forAll(Gen.choose(brokerConfig.groupFrom, brokerConfig.groupUntil - 1)) { mainGroup =>
        val blockFlow = genesisBlockFlow()

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
    val blockFlow0 = genesisBlockFlow()

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

    val blockFlow1 = storageBlockFlow()
    newBlocks1.map(_.hash).diff(blockFlow1.getAllTips.toArray).isEmpty is true

    val newBlocks2 = for {
      i <- 0 to 1
      j <- 0 to 1
    } yield mine(blockFlow1, ChainIndex.unsafe(i, j), onlyTxForIntra = true)
    newBlocks2.foreach { block =>
      val index = block.chainIndex
      if (index.relateTo(GroupIndex.unsafe(0))) {
        addAndCheck(blockFlow1, block, 4)
        blockFlow1.getChainWeight(block.hash) isE consensusConfig.maxMiningTarget * 2
      } else {
        addAndCheck(blockFlow1, block.header, 4)
      }
    }
    checkInBestDeps(GroupIndex.unsafe(0), blockFlow1, newBlocks2)
    checkBalance(blockFlow1, 0, genesisBalance - 2)
    newBlocks2.map(_.hash).contains(blockFlow1.getBestTipUnsafe) is true
  }

  behavior of "Sync"

  it should "compute sync locators and inventories" in {
    brokerConfig.groupNumPerBroker is 1 // the test only works in this case

    (0 until brokerConfig.groups).foreach { testToGroup =>
      val blockFlow0    = genesisBlockFlow()
      val testFromGroup = brokerConfig.groupFrom
      val blocks = (1 to 6).map { k =>
        val block =
          mine(blockFlow0, ChainIndex.unsafe(testFromGroup, testToGroup), onlyTxForIntra = true)
        addAndCheck(blockFlow0, block, k)
        block
      }
      val hashes0 = AVector.from(blocks.map(_.hash))
      val locators0: AVector[AVector[Hash]] =
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup)
            AVector(config.genesisBlocks(testFromGroup)(testToGroup).hash,
                    hashes0(0),
                    hashes0(1),
                    hashes0(3),
                    hashes0(4),
                    hashes0(5))
          else AVector(config.genesisBlocks(testFromGroup)(group).hash)
        }
      blockFlow0.getSyncLocators() isE locators0

      val blockFlow1 = genesisBlockFlow()
      val locators1: AVector[AVector[Hash]] = AVector.tabulate(config.broker.groups) { group =>
        AVector(config.genesisBlocks(testFromGroup)(group).hash)
      }
      blockFlow1.getSyncLocators() isE locators1

      blockFlow0.getSyncInventories(locators0) isE
        AVector.fill(groupConfig.groups)(AVector.empty[Hash])
      blockFlow0.getSyncInventories(locators1) isE
        AVector.tabulate(groupConfig.groups) { group =>
          if (group equals testToGroup) hashes0 else AVector.empty[Hash]
        }
      blockFlow1.getSyncInventories(locators0) isE
        AVector.fill(groupConfig.groups)(AVector.empty[Hash])
      blockFlow1.getSyncInventories(locators1) isE
        AVector.fill(groupConfig.groups)(AVector.empty[Hash])

      (0 until brokerConfig.brokerNum).foreach { id =>
        val remoteBrokerInfo = new BrokerGroupInfo {
          override def brokerId: Int          = id
          override def groupNumPerBroker: Int = brokerConfig.groupNumPerBroker
        }
        blockFlow0.getIntraSyncInventories(remoteBrokerInfo) isE
          (if (remoteBrokerInfo.groupFrom equals testToGroup) AVector(hashes0)
           else AVector(AVector.empty[Hash]))
        blockFlow1.getIntraSyncInventories(remoteBrokerInfo) isE AVector(AVector.empty[Hash])
      }

      val remoteBrokerInfo = new BrokerGroupInfo {
        override def brokerId: Int          = 0
        override def groupNumPerBroker: Int = brokerConfig.groups
      }
      blockFlow0.getIntraSyncInventories(remoteBrokerInfo) isE
        AVector.tabulate(brokerConfig.groups) { k =>
          if (k equals testToGroup) hashes0 else AVector.empty[Hash]
        }
      blockFlow1.getIntraSyncInventories(remoteBrokerInfo) isE
        AVector.fill(brokerConfig.groups)(AVector.empty[Hash])
    }
  }

  behavior of "Balance"

  it should "transfer token for inside a same group" in {
    val blockFlow = genesisBlockFlow()
    val testGroup = Random.source.nextInt(brokerConfig.groupNumPerBroker) + brokerConfig.groupFrom
    val block     = mine(blockFlow, ChainIndex.unsafe(testGroup, testGroup), onlyTxForIntra = true)
    block.nonCoinbase.nonEmpty is true
    addAndCheck(blockFlow, block, 1)

    val pubScript =
      block.nonCoinbase.head.unsigned.fixedOutputs.head.asInstanceOf[AssetOutput].lockupScript
    checkBalance(blockFlow, pubScript, 1)
    checkBalance(blockFlow, testGroup, genesisBalance - 1)
  }

  it should "transfer token for inter-group transactions" in {

    val anotherBroker = (brokerConfig.brokerId + 1 + Random.source.nextInt(
      brokerConfig.brokerNum - 1)) % brokerConfig.brokerNum
    val newConfigFixture = new AlephiumConfigFixture {
      override val configValues = Map(
        ("alephium.broker.broker-id", anotherBroker)
      )

      override lazy val genesisBalances = Test.genesisBalances
    }
    Test.genesisBalance is newConfigFixture.genesisBalance

    val anotherConfig   = newConfigFixture.config
    val anotherStorages = StoragesFixture.buildStorages(newConfigFixture.rootPath)
    config.genesisBlocks is anotherConfig.genesisBlocks
    val blockFlow0 = BlockFlow.fromGenesisUnsafe(config, storages)
    val blockFlow1 = BlockFlow.fromGenesisUnsafe(anotherConfig, anotherStorages)

    val fromGroup = Random.source.nextInt(brokerConfig.groupNumPerBroker) + brokerConfig.groupFrom
    val toGroup   = Random.source.nextInt(brokerConfig.groupNumPerBroker) + anotherConfig.broker.groupFrom
    val block     = mine(blockFlow0, ChainIndex.unsafe(fromGroup, toGroup))
    block.nonCoinbase.nonEmpty is true

    addAndCheck(blockFlow0, block, 1)
    checkBalance(blockFlow0, fromGroup, genesisBalance - 1)

    addAndCheck(blockFlow1, block, 1)
    val pubScript =
      block.nonCoinbase.head.unsigned.fixedOutputs.head.asInstanceOf[AssetOutput].lockupScript
    checkBalance(blockFlow1, pubScript, 1)
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
    blockFlow.getWeight(header) isE consensusConfig.maxMiningTarget * weightFactor
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
    blockFlow
      .getBestPersistedTrie(chainIndex.from)
      .toOption
      .get
      .getContractState(key)
      .map(_.fields) isE expected
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
    val bestDeps = (brokerConfig.groupFrom until brokerConfig.groupUntil)
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
    brokerConfig.contains(lockupScript.groupIndex) is true
    val query = blockFlow.getUtxos(lockupScript)
    query.toOption.get.sumBy(_._2.amount.v)
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      s"${txOutput.scriptHint}:${txOutput.amount}"
    }

    val address   = genesisBalances(brokerConfig.brokerId)._2
    val pubScript = LockupScript.p2pkh(address)
    val txOutputs = blockFlow.getUtxos(pubScript).toOption.get.map(_._2)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }
}
