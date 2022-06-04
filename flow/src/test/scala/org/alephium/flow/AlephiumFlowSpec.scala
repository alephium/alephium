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

package org.alephium.flow

import scala.annotation.tailrec
import scala.collection.mutable
import scala.language.implicitConversions

import akka.util.ByteString
import org.scalatest.{Assertion, BeforeAndAfterAll}

import org.alephium.flow.core.{BlockFlow, FlowUtils}
import org.alephium.flow.io.StoragesFixture
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.flow.validation.{BlockValidation, HeaderValidation, TxValidation}
import org.alephium.protocol._
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.serialize
import org.alephium.util._

// scalastyle:off number.of.methods
trait FlowFixture
    extends AlephiumSpec
    with AlephiumConfigFixture
    with StoragesFixture.Default
    with NumericHelpers {
  lazy val blockFlow: BlockFlow  = genesisBlockFlow()
  lazy val defaultUtxoLimit: Int = ALPH.MaxTxInputNum * 2

  lazy val keyManager: mutable.Map[LockupScript, PrivateKey] = mutable.Map.empty

  implicit def target2BigInt(target: Target): BigInt = BigInt(target.value)

  def genesisBlockFlow(): BlockFlow = BlockFlow.fromGenesisUnsafe(storages, config.genesisBlocks)
  def storageBlockFlow(): BlockFlow = BlockFlow.fromStorageUnsafe(config, storages)

  def isolatedBlockFlow(): BlockFlow = {
    val newStorages =
      StoragesFixture.buildStorages(rootPath.resolveSibling(Hash.generate.toHexString))
    BlockFlow.fromGenesisUnsafe(newStorages, config.genesisBlocks)
  }

  def addWithoutViewUpdate(blockFlow: BlockFlow, block: Block): Assertion = {
    val worldState =
      blockFlow.getCachedWorldState(block.blockDeps, block.chainIndex.from).rightValue
    blockFlow.add(block, Some(worldState)) isE ()
  }

  def addAndUpdateView(blockFlow: BlockFlow, block: Block): Assertion = {
    val worldState =
      blockFlow.getCachedWorldState(block.blockDeps, block.chainIndex.from).rightValue
    blockFlow.addAndUpdateView(block, Some(worldState)) isE ()
  }

  def getGenesisLockupScript(chainIndex: ChainIndex): LockupScript.Asset = {
    getGenesisLockupScript(chainIndex.from)
  }

  def getGenesisLockupScript(mainGroup: GroupIndex): LockupScript.Asset = {
    val (_, publicKey, _) = genesisKeys(mainGroup.value)
    LockupScript.p2pkh(publicKey)
  }

  def transferOnlyForIntraGroup(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      amount: U256 = ALPH.alph(1)
  ): Block = {
    if (chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from)) {
      transfer(blockFlow, chainIndex, amount)
    } else {
      emptyBlock(blockFlow, chainIndex)
    }
  }

  def emptyBlock(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    mine(blockFlow, chainIndex)((_, _) => AVector.empty[Transaction])
  }

  def simpleScript(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txScript: StatefulScript,
      gas: Int = 100000
  ): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from) && chainIndex.isIntraGroup)
    mine(blockFlow, chainIndex)(
      transferTxs(_, _, ALPH.alph(1), 1, Some(txScript), true, scriptGas = gas)
    )
  }

  def simpleScriptMulti(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      invokers: AVector[LockupScript.Asset],
      txScripts: AVector[StatefulScript]
  ): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from) && chainIndex.isIntraGroup)
    val zipped = invokers.mapWithIndex { case (invoker, index) =>
      invoker -> txScripts(index)
    }
    mine(blockFlow, chainIndex)(transferTxsMulti(_, _, zipped, ALPH.alph(1) / 100))
  }

  def transfer(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      amount: U256 = ALPH.alph(1),
      numReceivers: Int = 1,
      gasFeeInTheAmount: Boolean = true,
      lockTimeOpt: Option[TimeStamp] = None
  ): Block = {
    assume(blockFlow.brokerConfig.contains(chainIndex.from))
    mine(blockFlow, chainIndex)(
      transferTxs(_, _, amount, numReceivers, None, gasFeeInTheAmount, lockTimeOpt)
    )
  }

  def transfer(blockFlow: BlockFlow, from: PrivateKey, to: PublicKey, amount: U256): Block = {
    transfer(blockFlow, from, LockupScript.p2pkh(to), amount)
  }

  def transfer(
      blockFlow: BlockFlow,
      from: PrivateKey,
      to: LockupScript.Asset,
      amount: U256
  ): Block = {
    val unsigned = blockFlow
      .transfer(
        from.publicKey,
        to,
        None,
        amount,
        None,
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    val tx         = Transaction.from(unsigned, from)
    val chainIndex = tx.chainIndex
    mine(blockFlow, chainIndex)((_, _) => AVector(tx))
  }

  def transferTxs(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      amount: U256,
      numReceivers: Int,
      txScriptOpt: Option[StatefulScript],
      gasFeeInTheAmount: Boolean,
      lockTimeOpt: Option[TimeStamp] = None,
      scriptGas: Int = 100000
  ): AVector[Transaction] = {
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val gasAmount = txScriptOpt match {
      case None =>
        if (numReceivers > 1) {
          minimalGas addUnsafe defaultGasPerOutput.mulUnsafe(numReceivers)
        } else {
          minimalGas
        }
      case Some(_) => GasBox.unsafe(scriptGas)
    }
    val gasFee = defaultGasPrice * gasAmount
    val outputAmount =
      if (gasFeeInTheAmount) amount - gasFee.divUnsafe(numReceivers) else amount
    val outputInfos = AVector.fill(numReceivers) {
      val (toPrivateKey, toPublicKey)      = chainIndex.to.generateKey
      val lockupScript: LockupScript.Asset = LockupScript.p2pkh(toPublicKey)
      keyManager += lockupScript -> toPrivateKey
      TxOutputInfo(lockupScript, outputAmount, AVector.empty, lockTimeOpt)
    }
    val unsignedTx =
      blockFlow
        .transfer(
          publicKey,
          outputInfos,
          Some(gasAmount),
          defaultGasPrice,
          defaultUtxoLimit
        )
        .rightValue
        .rightValue
    val newUnsignedTx = unsignedTx.copy(scriptOpt = txScriptOpt)
    val tx            = Transaction.from(newUnsignedTx, privateKey)

    val txValidation = TxValidation.build
    txValidation.validateGrandPoolTxTemplate(tx.toTemplate, blockFlow) isE ()

    AVector(tx)
  }

  def transferTxsMulti(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      scripts: AVector[(LockupScript.Asset, StatefulScript)],
      amount: U256
  ): AVector[Transaction] = {
    scripts.map { case (lockupScript, txScript) =>
      transferTx(blockFlow, chainIndex, lockupScript, amount, Some(txScript))
    }
  }

  def transferTx(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      fromLockupScript: LockupScript,
      amount: U256,
      txScriptOpt: Option[StatefulScript]
  ): Transaction = {
    val privateKey = keyManager.getOrElse(fromLockupScript, genesisKeys(chainIndex.from.value)._1)
    val publicKey  = privateKey.publicKey

    val (toPrivateKey, toPublicKey) = chainIndex.to.generateKey
    val lockupScript                = LockupScript.p2pkh(toPublicKey)
    keyManager += lockupScript -> toPrivateKey

    val gasAmount = txScriptOpt match {
      case None    => minimalGas
      case Some(_) => GasBox.unsafe(100000)
    }

    val unsignedTx = blockFlow
      .transfer(
        publicKey,
        lockupScript,
        None,
        amount - defaultGasFee,
        Some(gasAmount),
        defaultGasPrice,
        defaultUtxoLimit
      )
      .rightValue
      .rightValue
    Transaction.from(unsignedTx.copy(scriptOpt = txScriptOpt), privateKey)
  }

  def doubleSpendingTx(blockFlow: BlockFlow, chainIndex: ChainIndex): Transaction = {
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val fromLockupScript           = LockupScript.p2pkh(publicKey)
    val unlockScript               = UnlockScript.p2pkh(publicKey)

    val balances = {
      val balances = blockFlow.getUsableUtxos(fromLockupScript, defaultUtxoLimit).rightValue
      balances ++ balances
    }
    balances.length is 2 // this function is used in this particular case

    val total  = balances.fold(U256.Zero)(_ addUnsafe _.output.amount)
    val amount = ALPH.alph(1)

    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)

    val inputs     = balances.map(_.ref).map(TxInput(_, unlockScript))
    val output     = TxOutput.asset(amount - defaultGasFee, lockupScript)
    val remaining  = TxOutput.asset(total - amount, fromLockupScript)
    val unsignedTx = UnsignedTransaction(None, inputs, AVector(output, remaining))
    Transaction.from(unsignedTx, privateKey)
  }

  def payableCall(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      script: StatefulScript,
      initialGas: Int = 200000,
      validation: Boolean = true
  ): Block = {
    mine(blockFlow, chainIndex)(payableCallTxs(_, _, script, initialGas, validation))
  }

  def payableCallTxTemplate(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      fromLockupScript: LockupScript.Asset,
      script: StatefulScript,
      initialGas: Int,
      validation: Boolean
  ): TransactionTemplate = {
    assume(chainIndex.isIntraGroup && blockFlow.brokerConfig.contains(chainIndex.from))
    val privateKey   = keyManager.getOrElse(fromLockupScript, genesisKeys(chainIndex.from.value)._1)
    val publicKey    = privateKey.publicKey
    val unlockScript = UnlockScript.p2pkh(publicKey)
    val balances     = blockFlow.getUsableUtxos(fromLockupScript, defaultUtxoLimit).rightValue
    val inputs       = balances.map(_.ref).map(TxInput(_, unlockScript))

    val unsignedTx =
      UnsignedTransaction(Some(script), inputs, AVector.empty)
        .copy(gasAmount = GasBox.unsafe(initialGas))
    val contractTx = TransactionTemplate.from(unsignedTx, privateKey)

    if (validation) {
      val txValidation = TxValidation.build
      txValidation.validateMempoolTxTemplate(contractTx, blockFlow) isE ()
    }
    contractTx
  }

  def payableCallTxs(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      script: StatefulScript,
      initialGas: Int,
      validation: Boolean
  ): AVector[Transaction] = {
    val mainGroup                  = chainIndex.from
    val (privateKey, publicKey, _) = genesisKeys(mainGroup.value)
    val fromLockupScript           = LockupScript.p2pkh(publicKey)
    val contractTx =
      payableCallTxTemplate(blockFlow, chainIndex, fromLockupScript, script, initialGas, validation)

    val (contractInputs, generateOutputs) =
      genInputsOutputs(blockFlow, chainIndex.from, contractTx, script)
    val fullTx = Transaction.from(contractTx.unsigned, contractInputs, generateOutputs, privateKey)
    AVector(fullTx)
  }

  def mineFromMemPool(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    val miner         = getGenesisLockupScript(chainIndex.to)
    val blockTemplate = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
    val block         = mine(blockFlow, chainIndex)((_, _) => blockTemplate.transactions.init)

    block.chainIndex is chainIndex

    block
  }

  def mineWithTxs(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txs: AVector[Transaction]
  ): Block = {
    val block = mine(blockFlow, chainIndex)((_, _) => txs)
    block.chainIndex is chainIndex
    block
  }

  def invalidNonceBlock(blockFlow: BlockFlow, chainIndex: ChainIndex): Block = {
    @tailrec
    def iter(current: Block): Block = {
      val tmp = Block(current.header.copy(nonce = Nonce.unsecureRandom()), current.transactions)
      if (!PoW.checkWork(tmp) && (tmp.chainIndex equals chainIndex)) tmp else iter(tmp)
    }
    iter(mineFromMemPool(blockFlow, chainIndex))
  }

  def mine(blockFlow: BlockFlow, chainIndex: ChainIndex)(
      prepareTxs: (BlockFlow, ChainIndex) => AVector[Transaction]
  ): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from)
    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)
    val txs              = prepareTxs(blockFlow, chainIndex)
    val parentTs         = blockFlow.getBlockHeaderUnsafe(deps.parentHash(chainIndex)).timestamp
    val blockTs          = FlowUtils.nextTimeStamp(parentTs)

    val target     = blockFlow.getNextHashTarget(chainIndex, deps).rightValue
    val coinbaseTx = Transaction.coinbase(chainIndex, txs, lockupScript, target, blockTs)
    mine0(blockFlow, chainIndex, deps, txs :+ coinbaseTx, blockTs, target)
  }

  def mineWithoutCoinbase(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      txs: AVector[Transaction],
      blockTs: TimeStamp
  ): Block = {
    val deps             = blockFlow.calBestDepsUnsafe(chainIndex.from)
    val (_, toPublicKey) = chainIndex.to.generateKey
    val lockupScript     = LockupScript.p2pkh(toPublicKey)
    val coinbaseTx =
      Transaction.coinbase(chainIndex, txs, lockupScript, consensusConfig.maxMiningTarget, blockTs)

    mine0(blockFlow, chainIndex, deps, txs :+ coinbaseTx, blockTs)
  }

  def mine(blockFlow: BlockFlow, template: BlockFlowTemplate): Block = {
    mine(
      blockFlow,
      template.index,
      template.deps,
      template.transactions,
      template.templateTs,
      template.target
    )
  }

  def mine(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      txs: AVector[Transaction],
      blockTs: TimeStamp,
      target: Target = consensusConfig.maxMiningTarget
  ): Block = {
    mine0(blockFlow, chainIndex, BlockDeps.unsafe(deps), txs, blockTs, target)
  }

  def reMine(blockFlow: BlockFlow, chainIndex: ChainIndex, block: Block): Block = {
    mine0(blockFlow, chainIndex, block.blockDeps, block.transactions, block.timestamp, block.target)
  }

  def mine0(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      deps: BlockDeps,
      txs: AVector[Transaction],
      blockTs: TimeStamp,
      target: Target = consensusConfig.maxMiningTarget
  ): Block = {
    val loosenDeps = blockFlow.looseUncleDependencies(deps, chainIndex, TimeStamp.now()).rightValue
    val depStateHash =
      blockFlow.getDepStateHash(loosenDeps, chainIndex.from).rightValue
    val txsHash = Block.calTxsHash(txs)
    Block(mineHeader(chainIndex, loosenDeps.deps, depStateHash, txsHash, blockTs, target), txs)
  }

  def mineHeader(
      chainIndex: ChainIndex,
      deps: AVector[BlockHash],
      depStateHash: Hash,
      txsHash: Hash,
      blockTs: TimeStamp,
      target: Target = consensusConfig.maxMiningTarget
  ): BlockHeader = {
    val blockDeps = BlockDeps.build(deps)

    @tailrec
    def iter(nonce: U256): BlockHeader = {
      val header = BlockHeader.unsafe(
        blockDeps,
        depStateHash,
        txsHash,
        blockTs,
        target,
        Nonce.unsecureRandom()
      )
      if (PoW.checkMined(header, chainIndex)) header else iter(nonce.addOneUnsafe())
    }

    iter(0)
  }

  private def genInputsOutputs(
      blockFlow: BlockFlow,
      mainGroup: GroupIndex,
      tx: TransactionTemplate,
      txScript: StatefulScript
  ): (AVector[ContractOutputRef], AVector[TxOutput]) = {
    val groupView  = blockFlow.getMutableGroupView(mainGroup).rightValue
    val blockEnv   = blockFlow.getDryrunBlockEnv(tx.chainIndex).rightValue
    val preOutputs = groupView.getPreOutputs(tx.unsigned.inputs).rightValue.get
    val result = StatefulVM
      .runTxScript(
        groupView.worldState.staging(),
        blockEnv,
        tx,
        preOutputs,
        txScript,
        tx.unsigned.gasAmount
      )
      .rightValue
    result.contractInputs -> result.generatedOutputs
  }

  def addAndCheck0(blockFlow: BlockFlow, block: Block): Unit = {
    val blockValidation = BlockValidation.build(blockFlow)
    val sideResult      = blockValidation.validate(block, blockFlow).rightValue
    blockFlow.addAndUpdateView(block, sideResult).rightValue
  }

  def addAndCheck(blockFlow: BlockFlow, blocks: Block*): Unit = {
    blocks.foreach { block =>
      addAndCheck0(blockFlow, block)
      checkOutputs(blockFlow, block)
    }
  }

  def addAndCheck(blockFlow: BlockFlow, block: Block, weightRatio: Int): Assertion = {
    addAndCheck0(blockFlow, block)
    blockFlow.getWeight(block) isE consensusConfig.minBlockWeight * weightRatio
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader): Assertion = {
    val headerValidation = HeaderValidation.build(blockFlow.brokerConfig, blockFlow.consensusConfig)
    headerValidation.validate(header, blockFlow).isRight is true
    blockFlow.addAndUpdateView(header).isRight is true
  }

  def addAndCheck(blockFlow: BlockFlow, header: BlockHeader, weightFactor: Int): Assertion = {
    addAndCheck(blockFlow, header)
    blockFlow.getWeight(header) isE consensusConfig.minBlockWeight * weightFactor
  }

  def checkBalance(blockFlow: BlockFlow, groupIndex: Int, expected: U256): Assertion = {
    val address   = genesisKeys(groupIndex)._2
    val pubScript = LockupScript.p2pkh(address)
    blockFlow
      .getUsableUtxos(pubScript, defaultUtxoLimit)
      .toOption
      .get
      .sumBy(_.output.amount.v: BigInt) is expected.toBigInt
  }

  def checkBalance(
      blockFlow: BlockFlow,
      pubScript: LockupScript.Asset,
      expected: U256
  ): Assertion = {
    blockFlow
      .getUsableUtxos(pubScript, defaultUtxoLimit)
      .rightValue
      .sumBy(_.output.amount.v: BigInt) is expected.v
  }

  def show(blockFlow: BlockFlow): String = {
    val tips = blockFlow.getAllTips
      .map { tip =>
        val weight = blockFlow.getWeightUnsafe(tip)
        val header = blockFlow.getBlockHeaderUnsafe(tip)
        val index  = header.chainIndex
        val deps   = header.blockDeps.deps.map(_.shortHex).mkString("-")
        s"weight: $weight, from: ${index.from}, to: ${index.to} hash: ${tip.shortHex}, deps: $deps"
      }
      .mkString("", "\n", "\n")
    val bestDeps = brokerConfig.groupRange
      .map { group =>
        val bestDeps    = blockFlow.getBestDeps(GroupIndex.unsafe(group))
        val bestDepsStr = bestDeps.deps.map(_.shortHex).mkString("-")
        s"group $group, bestDeps: $bestDepsStr"
      }
      .mkString("", "\n", "\n")
    tips ++ bestDeps
  }

  def getBalance(blockFlow: BlockFlow, address: PublicKey): U256 = {
    val lockupScript = LockupScript.p2pkh(address)
    brokerConfig.contains(lockupScript.groupIndex) is true
    val query = blockFlow.getUsableUtxos(lockupScript, defaultUtxoLimit)
    U256.unsafe(query.rightValue.sumBy(_.output.amount.v: BigInt).underlying())
  }

  def showBalances(blockFlow: BlockFlow): Unit = {
    def show(txOutput: TxOutput): String = {
      s"${txOutput.hint}:${txOutput.amount}"
    }

    val address   = genesisKeys(brokerConfig.brokerId)._2
    val pubScript = LockupScript.p2pkh(address)
    val txOutputs = blockFlow.getUsableUtxos(pubScript, defaultUtxoLimit).rightValue.map(_.output)
    print(txOutputs.map(show).mkString("", ";", "\n"))
  }

  def getTokenBalance(
      blockFlow: BlockFlow,
      lockupScript: LockupScript.Asset,
      tokenId: TokenId
  ): U256 = {
    brokerConfig.contains(lockupScript.groupIndex) is true
    val utxos = blockFlow.getUsableUtxos(lockupScript, defaultUtxoLimit).rightValue
    utxos.fold(U256.Zero) { case (acc, utxo) =>
      val sum = utxo.output.tokens.fold(U256.Zero) { case (acc, (id, amount)) =>
        if (tokenId equals id) acc.addUnsafe(amount) else acc
      }
      acc.addUnsafe(sum)
    }
  }

  def checkState(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      key: Hash,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      numAssets: Int = 2,
      numContracts: Int = 2
  ): Assertion = {
    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(key).fold(throw _, identity)

    contractState.fields is fields
    contractState.contractOutputRef is outputRef

    worldState
      .getAssetOutputs(ByteString.empty, Int.MaxValue, (_, _) => true)
      .rightValue
      .length is numAssets
    worldState.getContractOutputs(ByteString.empty, Int.MaxValue).rightValue.length is numContracts
  }

  def checkOutputs(blockFlow: BlockFlow, block: Block): Unit = {
    val chainIndex = block.chainIndex
    val worldState =
      blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    if (chainIndex.isIntraGroup) {
      block.nonCoinbase.foreach { tx =>
        tx.allOutputs.foreachWithIndex { case (output, index) =>
          val outputRef = output match {
            case assetOutput: AssetOutput =>
              TxOutputRef.from(assetOutput, TxOutputRef.key(tx.id, index))
            case contractOutput: ContractOutput =>
              ContractOutputRef.unsafe(contractOutput.hint, contractOutput.lockupScript.contractId)
          }

          worldState.existOutput(outputRef) isE true
        }
      }
    }
  }

  def debugTxGas(blockFlow: BlockFlow, chainIndex: ChainIndex, tx0: Transaction): Unit = {
    val initialGas  = tx0.unsigned.gasAmount
    val worldState  = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val prevOutputs = worldState.getPreOutputs(tx0).rightValue
    val blockEnv =
      BlockEnv(networkConfig.networkId, TimeStamp.now(), consensusConfig.maxMiningTarget, None)
    val txValidation = TxValidation.build
    val gasLeft      = txValidation.checkGasAndWitnesses(tx0, prevOutputs, blockEnv).rightValue
    val gasUsed      = initialGas.use(gasLeft).rightValue
    print(s"length: ${tx0.unsigned.inputs.length}\n")
    print(s"gasUsed $gasUsed\n")
    import org.alephium.protocol.vm.GasSchedule._
    val estimate = txBaseGas addUnsafe
      txInputBaseGas.mulUnsafe(tx0.unsigned.inputs.length) addUnsafe
      txOutputBaseGas.mulUnsafe(tx0.outputsLength) addUnsafe
      GasBox.unsafe(2054 * tx0.unsigned.inputs.length)
    print(s"estimate: $estimate\n")
  }

  def contractCreation(
      code: StatefulContract,
      initialState: AVector[Val],
      lockupScript: LockupScript.Asset,
      alphAmount: U256,
      newTokenAmount: Option[U256] = None
  ): StatefulScript = {
    val address  = Address.Asset(lockupScript)
    val codeRaw  = Hex.toHexString(serialize(code))
    val stateRaw = Hex.toHexString(serialize(initialState))
    val creation = newTokenAmount match {
      case Some(amount) => s"createContractWithToken!(#$codeRaw, #$stateRaw, ${amount.v})"
      case None         => s"createContract!(#$codeRaw, #$stateRaw)"
    }
    val scriptRaw =
      s"""
         |TxScript Foo {
         |  approveAlph!(@${address.toBase58}, ${alphAmount.v})
         |  $creation
         |}
         |""".stripMargin
    Compiler.compileTxScript(scriptRaw).rightValue
  }

  lazy val outOfGasTxTemplate = {
    val chainIndex = ChainIndex.unsafe(0, 0)
    val input =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contract = Compiler.compileContract(input).rightValue
    val txScript =
      contractCreation(contract, AVector.empty, getGenesisLockupScript(chainIndex), ALPH.alph(1))
    payableCallTxTemplate(
      blockFlow,
      chainIndex,
      getGenesisLockupScript(chainIndex),
      txScript,
      33000,
      validation = false
    )
  }
}

trait AlephiumFlowSpec extends AlephiumSpec with BeforeAndAfterAll with FlowFixture {
  override def afterAll(): Unit = {
    super.afterAll()
    cleanStorages()
  }
}

class AlephiumFlowActorSpec extends AlephiumActorSpec with AlephiumFlowSpec {
  override def afterAll(): Unit = {
    super.afterAll()
    cleanStorages()
  }
}
