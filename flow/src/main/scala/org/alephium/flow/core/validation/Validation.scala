package org.alephium.flow.core.validation

import scala.collection.mutable

import org.alephium.crypto.{ED25519, ED25519Signature}
import org.alephium.flow.core._
import org.alephium.flow.io.{IOError, IOResult}
import org.alephium.flow.platform.PlatformConfig
import org.alephium.flow.trie.WorldState
import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.config.{GroupConfig, ScriptConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.serde._
import org.alephium.util.{AVector, EitherF, Forest, TimeStamp, U64}

abstract class Validation[T <: FlowData, S <: ValidationStatus]() {
  def validate(data: T, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): IOResult[S]

  def validateUntilDependencies(data: T, flow: BlockFlow, isSyncing: Boolean): IOResult[S]

  def validateAfterDependencies(data: T, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[S]
}

// scalastyle:off number.of.methods
object Validation {
  import ValidationStatus._

  private[validation] def validateHeaderUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow,
      isSyncing: Boolean): HeaderValidationResult = {
    for {
      _ <- checkTimeStamp(header, isSyncing)
      _ <- checkWorkAmount(header)
      _ <- checkDependencies(header, flow)
    } yield ()
  }

  private[validation] def validateHeaderAfterDependencies(header: BlockHeader, flow: BlockFlow)(
      implicit config: PlatformConfig): HeaderValidationResult = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
    } yield ()
  }

  private[validation] def validateHeader(header: BlockHeader, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): HeaderValidationResult = {
    for {
      _ <- validateHeaderUntilDependencies(header, flow, isSyncing)
      _ <- validateHeaderAfterDependencies(header, flow)
    } yield ()
  }

  private[validation] def validateBlockUntilDependencies(
      block: Block,
      flow: BlockFlow,
      isSyncing: Boolean): BlockValidationResult = {
    validateHeaderUntilDependencies(block.header, flow, isSyncing)
  }

  private[validation] def validateBlockAfterDependencies(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult = {
    for {
      _ <- validateHeaderAfterDependencies(block.header, flow)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlock(block: Block, flow: BlockFlow, isSyncing: Boolean)(
      implicit config: PlatformConfig): BlockValidationResult = {
    for {
      _ <- validateHeader(block.header, flow, isSyncing)
      _ <- validateBlockAfterHeader(block, flow)
    } yield ()
  }

  private[validation] def validateBlockAfterHeader(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): BlockValidationResult = {
    for {
      _ <- checkGroup(block)
      _ <- checkNonEmptyTransactions(block)
      _ <- checkCoinbase(block)
      _ <- checkMerkleRoot(block)
      _ <- checkNonCoinbases(block, flow)
    } yield ()
  }

  private[validation] def validateNonCoinbaseTx(tx: Transaction, flow: BlockFlow)(
      implicit config: PlatformConfig): TxValidationResult = {
    val index = ChainIndex(tx.fromGroup, tx.toGroup)
    for {
      trie <- ValidationStatus.from(flow.getBestTrie(index))
      _    <- checkNonCoinbaseTx(index, tx, trie)
    } yield ()
  }

  /*
   * The following functions are all the check functions behind validations
   */

  private[validation] def checkGroup(block: Block)(
      implicit config: PlatformConfig): BlockValidationResult = {
    if (block.chainIndex.relateTo(config.brokerInfo)) validBlock
    else invalidBlock(InvalidGroup)
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private[validation] def checkTimeStamp(header: BlockHeader,
                                         isSyncing: Boolean): HeaderValidationResult = {
    val now      = TimeStamp.now()
    val headerTs = header.timestamp

    val ok1 = headerTs < now.plusHoursUnsafe(1)
    val ok2 = isSyncing || (headerTs > now.plusHoursUnsafe(-1)) // Note: now -1hour is always positive
    if (ok1 && ok2) validHeader else invalidHeader(InvalidTimeStamp)
  }

  private[validation] def checkWorkAmount[T <: FlowData](data: T): HeaderValidationResult = {
    val current = BigInt(1, data.hash.bytes.toArray)
    assert(current >= 0)
    if (current <= data.target) validHeader else invalidHeader(InvalidWorkAmount)
  }

  private[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain)(
      implicit config: GroupConfig): HeaderValidationResult = {
    headerChain.getHashTarget(header.parentHash) match {
      case Left(error) => Left(Left(error))
      case Right(target) =>
        if (target == header.target) validHeader else invalidHeader(InvalidWorkTarget)
    }
  }

  private[validation] def checkDependencies(header: BlockHeader,
                                            flow: BlockFlow): HeaderValidationResult = {
    header.blockDeps.filterNotE(flow.contains) match {
      case Left(error) => Left(Left(error))
      case Right(missings) =>
        if (missings.isEmpty) validHeader else invalidHeader(MissingDeps(missings))
    }
  }

  private[validation] def checkNonEmptyTransactions(block: Block): BlockValidationResult = {
    if (block.transactions.nonEmpty) validBlock else invalidBlock(EmptyTransactionList)
  }

  private[validation] def checkCoinbase(block: Block): BlockValidationResult = {
    val coinbase = block.coinbase // Note: validateNonEmptyTransactions first pls!
    val unsigned = coinbase.unsigned
    if (unsigned.inputs.isEmpty && unsigned.fixedOutputs.length == 1 && coinbase.generatedOutputs.isEmpty && coinbase.signatures.isEmpty)
      validBlock
    else invalidBlock(InvalidCoinbase)
  }

  // TODO: use Merkle hash for transactions
  private[validation] def checkMerkleRoot(block: Block): BlockValidationResult = {
    if (block.header.txsHash == Hash.hash(block.transactions)) validBlock
    else invalidBlock(InvalidMerkleRoot)
  }

  private[validation] def checkNonCoinbases(block: Block, flow: BlockFlow)(
      implicit config: PlatformConfig): TxsValidationResult = {
    val index      = block.chainIndex
    val brokerInfo = config.brokerInfo
    assert(index.relateTo(brokerInfo))

    if (brokerInfo.contains(index.from)) {
      val result = for {
        _    <- checkBlockDoubleSpending(block)
        trie <- ValidationStatus.from(flow.getTrie(block))
        _    <- block.nonCoinbase.foreachE(checkBlockNonCoinbase(index, _, trie))
      } yield ()
      convert(result)
    } else {
      validTxs
    }
  }

  private[validation] def checkBlockNonCoinbase(index: ChainIndex,
                                                tx: Transaction,
                                                worldState: WorldState)(
      implicit config: GroupConfig with ScriptConfig): TxValidationResult = {
    for {
      _ <- checkNonEmpty(tx)
      _ <- checkOutputValue(tx)
      _ <- checkChainIndex(index, tx)
      _ <- checkSpending(tx, worldState)
    } yield ()
  }

  private[validation] def checkNonCoinbaseTx(index: ChainIndex,
                                             tx: Transaction,
                                             worldState: WorldState)(
      implicit config: GroupConfig with ScriptConfig): TxValidationResult = {
    for {
      _ <- checkNonEmpty(tx)
      _ <- checkOutputValue(tx)
      _ <- checkChainIndex(index, tx)
      _ <- checkTxDoubleSpending(tx)
      _ <- checkSpending(tx, worldState)
    } yield ()
  }

  private[validation] def checkNonEmpty(tx: Transaction): TxValidationResult = {
    if (tx.unsigned.inputs.isEmpty) {
      invalidTx(EmptyInputs)
    } else if (tx.outputsLength == 0) {
      invalidTx(EmptyOutputs)
    } else {
      validTx
    }
  }

  private[validation] def checkOutputValue(tx: Transaction): TxValidationResult = {
    tx.alfAmountInOutputs match {
      case Some(sum) if sum < ALF.MaxALFValue => validTx
      case _                                  => invalidTx(BalanceOverFlow)
    }
  }

  private def getToGroup(output: TxOutput, default: GroupIndex)(
      implicit config: GroupConfig): GroupIndex = output match {
    case assetOutput: AssetOutput => assetOutput.toGroup
    case _: ContractOutput        => default
  }

  private[validation] def checkChainIndex(index: ChainIndex, tx: Transaction)(
      implicit config: GroupConfig): TxValidationResult = {
    val fromOk    = tx.unsigned.inputs.forall(_.fromGroup == index.from)
    val fromGroup = tx.fromGroup
    val toGroups  = (0 until tx.outputsLength).map(i => getToGroup(tx.getOutput(i), fromGroup))
    val toOk      = toGroups.forall(groupIndex => groupIndex == index.from || groupIndex == index.to)
    val existed   = toGroups.view.take(tx.unsigned.fixedOutputs.length).contains(index.to)
    if (fromOk && toOk && existed) validTx else invalidTx(InvalidChainIndex)
  }

  private[validation] def checkBlockDoubleSpending(block: Block): TxValidationResult = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    block.nonCoinbase.foreachE { tx =>
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) invalidTx(DoubleSpent)
        else {
          utxoUsed += input.outputRef
          validTx
        }
      }
    }
  }

  private[validation] def checkTxDoubleSpending(tx: Transaction): TxValidationResult = {
    val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
    tx.unsigned.inputs.foreachE { input =>
      if (utxoUsed.contains(input.outputRef)) invalidTx(DoubleSpent)
      else {
        utxoUsed += input.outputRef
        validTx
      }
    }
  }

  private[validation] def checkSpending(tx: Transaction,
                                        worldState: WorldState): TxValidationResult = {
    val query = tx.unsigned.inputs.mapE { input =>
      worldState.get(input.outputRef)
    }
    query match {
      case Right(preOutputs)                 => checkSpending(tx, preOutputs)
      case Left(IOError.RocksDB.keyNotFound) => invalidTx(NonExistInput)
      case Left(error)                       => Left(Left(error))
    }
  }

  private[validation] def checkSpending(tx: Transaction,
                                        preOutputs: AVector[TxOutput]): TxValidationResult = {
    for {
      _ <- checkBalance(tx, preOutputs)
      _ <- checkWitnesses(tx, preOutputs)
    } yield ()
  }

  private[validation] def checkBalance(tx: Transaction,
                                       preOutputs: AVector[TxOutput]): TxValidationResult = {
    for {
      _ <- checkAlfBalance(tx, preOutputs)
      _ <- checkTokenBalance(tx, preOutputs)
    } yield ()
  }

  private[validation] def checkAlfBalance(tx: Transaction,
                                          preOutputs: AVector[TxOutput]): TxValidationResult = {
    val inputSum = preOutputs.fold(U64.Zero)(_ addUnsafe _.amount)
    tx.alfAmountInOutputs match {
      case Some(outputSum) if outputSum <= inputSum => validTx
      case Some(_)                                  => invalidTx(InvalidBalance)
      case None                                     => invalidTx(BalanceOverFlow)
    }
  }

  private[validation] def checkTokenBalance(tx: Transaction,
                                            preOutputs: AVector[TxOutput]): TxValidationResult = {
    for {
      inputBalances  <- computeTokenBalances(preOutputs)
      outputBalances <- computeTokenBalances(tx.unsigned.fixedOutputs ++ tx.generatedOutputs)
      _ <- {
        val ok = outputBalances.forall {
          case (tokenId, balance) =>
            (inputBalances.contains(tokenId) && inputBalances(tokenId) >= balance) || tokenId == tx.unsigned.inputs.head.hash
        }
        if (ok) validTx else invalidTx(InvalidBalance)
      }
    } yield ()
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private[validation] def computeTokenBalances(
      outputs: AVector[TxOutput]): Either[TxValidationError, mutable.Map[TokenId, U64]] =
    try {
      val balances = mutable.Map.empty[TokenId, U64]
      outputs.foreach {
        case output: AssetOutput =>
          output.tokens.foreach {
            case (tokenId, amount) =>
              val total = balances.getOrElse(tokenId, U64.Zero)
              balances.put(tokenId, total.add(amount).get)
          }
        case _ => ()
      }
      Right(balances)
    } catch {
      case _: NoSuchElementException => Left(Right(BalanceOverFlow))
    }

  // TODO: signatures might not be 1-to-1 mapped to inputs
  private[validation] def checkWitnesses(tx: Transaction,
                                         preOutputs: AVector[TxOutput]): TxValidationResult = {
    assume(tx.unsigned.inputs.length == preOutputs.length)
    EitherF.foreachTry(preOutputs.indices) { idx =>
      val unlockScript = tx.unsigned.inputs(idx).unlockScript
      val signature    = tx.signatures(idx)
      preOutputs(idx) match {
        case assetOutput: AssetOutput =>
          checkLockupScript(tx, assetOutput.lockupScript, unlockScript, signature)
        case _: ContractOutput =>
          ???
      }
    }
  }

  private[validation] def checkLockupScript(tx: Transaction,
                                            lockupScript: LockupScript,
                                            unlockScript: UnlockScript,
                                            signature: ED25519Signature): TxValidationResult = {
    (lockupScript, unlockScript) match {
      case (lock: LockupScript.P2PKH, unlock: UnlockScript.P2PKH) =>
        checkP2pkh(tx, lock, unlock, signature)
      case (lock: LockupScript.P2SH, unlock: UnlockScript.P2SH) =>
        checkP2SH(tx, lock, unlock, signature)
      case (lock: LockupScript.P2S, unlock: UnlockScript.P2S) =>
        checkP2S(tx, lock, unlock, signature)
      case _ =>
        invalidTx(InvalidUnlockScriptType)
    }
  }

  private[validation] def checkP2pkh(tx: Transaction,
                                     lock: LockupScript.P2PKH,
                                     unlock: UnlockScript.P2PKH,
                                     signature: ED25519Signature): TxValidationResult = {
    if (Hash.hash(unlock.publicKey.bytes) != lock.pkHash) {
      invalidTx(InvalidPublicKeyHash)
    } else if (!ED25519.verify(tx.hash.bytes, signature, unlock.publicKey)) {
      invalidTx(InvalidSignature)
    } else validTx
  }

  private[validation] def checkP2SH(tx: Transaction,
                                    lock: LockupScript.P2SH,
                                    unlock: UnlockScript.P2SH,
                                    signature: ED25519Signature): TxValidationResult = {
    if (Hash.hash(serialize(unlock.script)) != lock.scriptHash) {
      invalidTx(InvalidScriptHash)
    } else {
      checkScript(tx, unlock.script, unlock.params, signature)
    }
  }

  private[validation] def checkP2S(tx: Transaction,
                                   lock: LockupScript.P2S,
                                   unlock: UnlockScript.P2S,
                                   signature: ED25519Signature): TxValidationResult = {
    checkScript(tx, lock.script, unlock.params, signature)
  }

  private[validation] def checkScript(tx: Transaction,
                                      script: StatelessScript,
                                      params: AVector[Val],
                                      signature: ED25519Signature): TxValidationResult = {
    val context = StatelessContext(tx.hash, signature)
    StatelessVM.execute(context, script, AVector.empty, params) match {
      case Right(_) => validTx // TODO: handle returns
      case Left(e)  => invalidTx(InvalidUnlockScript(e))
    }
  }

  /*
   * The following functions are for other type of validation
   */

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def validateFlowDAG[T <: FlowData](datas: AVector[T])(
      implicit config: GroupConfig): Option[AVector[Forest[Hash, T]]] = {
    val splits = datas.splitBy(_.chainIndex)
    val builds = splits.map(ds => Forest.tryBuild[Hash, T](ds, _.hash, _.parentHash))
    if (builds.forall(_.nonEmpty)) Some(builds.map(_.get)) else None
  }

  def validateMined[T <: FlowData](data: T, index: ChainIndex)(
      implicit config: GroupConfig): Boolean = {
    data.chainIndex == index && checkWorkAmount(data).isRight
  }
}
// scalastyle:on number.of.methods
