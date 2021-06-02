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

package org.alephium.flow.validation

import scala.collection.mutable

import org.alephium.flow.core.{BlockFlow, FlowUtils}
import org.alephium.io.IOError
import org.alephium.protocol.{ALF, Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{OutOfGas => _, _}
import org.alephium.util.{AVector, EitherF, TimeStamp, U256}

trait TxValidation {
  import ValidationStatus._

  implicit def groupConfig: GroupConfig

  def validateTxTemplate(
      tx: TransactionTemplate,
      flow: BlockFlow,
      validate: (ChainIndex, Transaction, BlockFlow) => TxValidationResult[Unit]
  ): TxValidationResult[Unit] = {
    tx.unsigned.scriptOpt match {
      case None =>
        val fullTx = FlowUtils.convertNonScriptTx(tx)
        for {
          chainIndex <- getChainIndex(tx)
          _          <- validate(chainIndex, fullTx, flow)
        } yield ()
      case Some(script) =>
        for {
          chainIndex <- getChainIndex(tx)
          worldState <- from(flow.getBestCachedWorldState(chainIndex.from))
          fullTx <- StatefulVM.runTxScript(worldState, tx, script, tx.unsigned.startGas) match {
            case Left(error)   => invalidTx(TxScriptExeFailed(error))
            case Right(result) => validTx(FlowUtils.convertSuccessfulTx(tx, result))
          }
          _ <- validate(chainIndex, fullTx, flow)
        } yield ()
    }
  }

  def validateMempoolTxTemplate(
      tx: TransactionTemplate,
      flow: BlockFlow
  ): TxValidationResult[Unit] = {
    validateTxTemplate(tx, flow, validateMempoolTx)
  }

  def validateMempoolTx(
      chainIndex: ChainIndex,
      tx: Transaction,
      flow: BlockFlow
  ): TxValidationResult[Unit] = {
    for {
      _          <- checkStateless(chainIndex, tx, checkDoubleSpending = true)
      worldState <- from(flow.getBestCachedWorldState(tx.chainIndex.from))
      _          <- checkStateful(chainIndex, tx, TimeStamp.now(), worldState, None)
    } yield ()
  }

  def validateGrandPoolTxTemplate(
      tx: TransactionTemplate,
      flow: BlockFlow
  ): TxValidationResult[Unit] = {
    validateTxTemplate(tx, flow, validateGrandPoolTx)
  }

  def validateGrandPoolTx(
      chainIndex: ChainIndex,
      tx: Transaction,
      flow: BlockFlow
  ): TxValidationResult[Unit] = {
    for {
      _ <- checkStateless(chainIndex, tx, checkDoubleSpending = true)
      preOutputs <- flow.getPreOutputs(tx) match {
        case Right(Some(outputs)) => Right(outputs)
        case Right(None)          => Left(Right(NonExistInput))
        case Left(error)          => Left(Left(error))
      }
      _ <- checkStatefulExceptTxScript(tx, TimeStamp.now(), preOutputs, None)
    } yield ()
  }

  protected[validation] def checkBlockTx(
      chainIndex: ChainIndex,
      tx: Transaction,
      header: BlockHeader,
      worldState: WorldState.Cached
  ): TxValidationResult[Unit] = {
    for {
      _ <- checkStateless(
        header.chainIndex,
        tx,
        checkDoubleSpending = false
      ) // it's already checked in BlockValidation
      _ <- checkStateful(chainIndex, tx, header.timestamp, worldState, None)
    } yield ()
  }

  protected[validation] def checkCoinbase(
      tx: Transaction,
      header: BlockHeader,
      worldState: WorldState.Cached,
      coinbaseNetReward: U256
  ): TxValidationResult[Unit] = {
    for {
      _ <- checkStateless(
        header.chainIndex,
        tx,
        checkDoubleSpending = false
      )
      _ <- checkStateful(
        header.chainIndex,
        tx,
        header.timestamp,
        worldState,
        Some(coinbaseNetReward)
      )
    } yield ()
  }

  protected[validation] def checkStateless(
      chainIndex: ChainIndex,
      tx: Transaction,
      checkDoubleSpending: Boolean
  ): TxValidationResult[Unit] = {
    for {
      _ <- checkInputNum(tx)
      _ <- checkOutputNum(tx, chainIndex.isIntraGroup)
      _ <- checkGasBound(tx)
      _ <- checkOutputAmount(tx)
      _ <- checkChainIndex(tx, chainIndex)
      _ <- checkUniqueInputs(tx, checkDoubleSpending)
      _ <- checkOutputDataSize(tx)
    } yield ()
  }
  protected[validation] def checkStateful(
      chainIndex: ChainIndex,
      tx: Transaction,
      headerTs: TimeStamp,
      worldState: WorldState.Cached,
      coinbaseNetReward: Option[U256]
  ): TxValidationResult[Unit] = {
    for {
      preOutputs   <- getPreOutputs(tx, worldState)
      gasRemaining <- checkStatefulExceptTxScript(tx, headerTs, preOutputs, coinbaseNetReward)
      _            <- checkTxScript(chainIndex, tx, gasRemaining, worldState)
    } yield ()
  }
  protected[validation] def checkStatefulExceptTxScript(
      tx: Transaction,
      headerTs: TimeStamp,
      preOutputs: AVector[TxOutput],
      coinbaseNetReward: Option[U256]
  ): TxValidationResult[GasBox] = {
    for {
      _            <- checkLockTime(preOutputs, headerTs)
      _            <- checkAlfBalance(tx, preOutputs, coinbaseNetReward)
      _            <- checkTokenBalance(tx, preOutputs)
      gasRemaining <- checkWitnesses(tx, preOutputs)
    } yield gasRemaining
  }

  protected[validation] def getPreOutputs(
      tx: Transaction,
      worldState: MutableWorldState
  ): TxValidationResult[AVector[TxOutput]]

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkOutputNum(tx: Transaction, isIntraGroup: Boolean): TxValidationResult[Unit]
  protected[validation] def checkGasBound(tx: TransactionAbstract): TxValidationResult[Unit]
  protected[validation] def checkOutputAmount(tx: Transaction): TxValidationResult[U256]
  protected[validation] def getChainIndex(tx: TransactionAbstract): TxValidationResult[ChainIndex]
  protected[validation] def checkChainIndex(tx: Transaction, expected: ChainIndex): TxValidationResult[Unit]
  protected[validation] def checkUniqueInputs(tx: Transaction, checkDoubleSpending: Boolean): TxValidationResult[Unit]
  protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit]

  protected[validation] def checkLockTime(preOutputs: AVector[TxOutput], headerTs: TimeStamp): TxValidationResult[Unit]
  protected[validation] def checkAlfBalance(tx: Transaction, preOutputs: AVector[TxOutput], coinbaseNetReward: Option[U256]): TxValidationResult[Unit]
  protected[validation] def checkTokenBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkWitnesses(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[GasBox]
  protected[validation] def checkTxScript(
      chainIndex: ChainIndex,
      tx: Transaction,
      gasRemaining: GasBox,
      worldState: WorldState.Cached): TxValidationResult[Unit] // TODO: optimize it with preOutputs
  // format: on
}

// Note: only non-coinbase transactions are validated here
object TxValidation {
  import ValidationStatus._

  def build(implicit groupConfig: GroupConfig): TxValidation = new Impl()

  class Impl(implicit val groupConfig: GroupConfig) extends TxValidation {
    protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit] = {
      val inputNum = tx.unsigned.inputs.length
      // inputNum can be 0 due to coinbase tx
      if (inputNum > ALF.MaxTxInputNum) {
        invalidTx(TooManyInputs)
      } else {
        validTx(())
      }
    }

    protected[validation] def checkOutputNum(
        tx: Transaction,
        isIntraGroup: Boolean
    ): TxValidationResult[Unit] = {
      if (isIntraGroup) checkIntraGroupOutputNum(tx) else checkInterGroupOutputNum(tx)
    }
    protected[validation] def checkIntraGroupOutputNum(
        tx: Transaction
    ): TxValidationResult[Unit] = {
      checkOutputNumCommon(tx.outputsLength)
    }
    protected[validation] def checkInterGroupOutputNum(
        tx: Transaction
    ): TxValidationResult[Unit] = {
      if (tx.generatedOutputs.nonEmpty) {
        invalidTx(GeneratedOutputForInterGroupTx)
      } else {
        checkOutputNumCommon(tx.unsigned.fixedOutputs.length)
      }
    }
    protected[validation] def checkOutputNumCommon(
        outputNum: Int
    ): TxValidationResult[Unit] = {
      if (outputNum == 0) {
        invalidTx(NoOutputs)
      } else if (outputNum > ALF.MaxTxOutputNum) {
        invalidTx(TooManyOutputs)
      } else {
        validTx(())
      }
    }

    protected[validation] def checkGasBound(tx: TransactionAbstract): TxValidationResult[Unit] = {
      if (!GasBox.validate(tx.unsigned.startGas)) {
        invalidTx(InvalidStartGas)
      } else if (!checkGasPrice(tx.unsigned.gasPrice)) {
        invalidTx(InvalidGasPrice)
      } else {
        validTx(())
      }
    }

    private def checkGasPrice(gasPrice: GasPrice): Boolean = {
      gasPrice.value > U256.Zero && gasPrice.value < ALF.MaxALFValue
    }

    protected[validation] def checkOutputAmount(tx: Transaction): TxValidationResult[U256] = {
      for {
        _      <- checkEachOutputAmount(tx)
        amount <- checkAlfOutputAmount(tx)
      } yield amount
    }

    protected[validation] def checkEachOutputAmount(
        tx: Transaction
    ): TxValidationResult[Unit] = {
      val ok = tx.unsigned.fixedOutputs.forall(checkOutputAmount) &&
        tx.generatedOutputs.forall(checkOutputAmount)
      if (ok) validTx(()) else invalidTx(AmountIsDustOrZero)
    }

    @inline private def checkOutputAmount(
        output: TxOutput
    ): Boolean = {
      output.amount >= dustUtxoAmount && output.tokens.forall(_._2.nonZero)
    }

    protected[validation] def checkAlfOutputAmount(tx: Transaction): TxValidationResult[U256] = {
      tx.alfAmountInOutputs match {
        case Some(total) => validTx(total)
        case None        => invalidTx(BalanceOverFlow)
      }
    }

    protected[validation] def checkChainIndex(
        tx: Transaction,
        expected: ChainIndex
    ): TxValidationResult[Unit] = {
      val ok1 = tx.unsigned.inputs.forall(_.fromGroup == expected.from)
      val ok2 = tx.unsigned.fixedOutputs.forall(output => expected.relateTo(output.toGroup))
      val ok3 = tx.generatedOutputs.forall {
        case output: AssetOutput => expected.relateTo(output.toGroup)
        case _                   => true
      }
      // when the transaction in for intra group, the fixedOutputs might be empty
      val ok4 = expected.isIntraGroup || tx.unsigned.fixedOutputs.exists(_.toGroup == expected.to)
      if (!ok1) {
        invalidTx(InvalidInputGroupIndex)
      } else if ((!ok2) || (!ok3) || (!ok4)) {
        invalidTx(InvalidOutputGroupIndex)
      } else {
        validTx(())
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    protected[validation] def getChainIndex(
        tx: TransactionAbstract
    ): TxValidationResult[ChainIndex] = {
      val inputIndexes = tx.unsigned.inputs.map(_.fromGroup).toSet
      if (inputIndexes.size != 1) {
        invalidTx(InvalidInputGroupIndex)
      } else {
        val fromIndex = inputIndexes.head
        val outputIndexes =
          (0 until tx.outputsLength).view
            .map(index => getToGroup(tx.getOutput(index), fromIndex))
            .filter(_ != fromIndex)
            .toSet
        outputIndexes.size match {
          case 0 => validTx(ChainIndex(fromIndex, fromIndex))
          case 1 => validTx(ChainIndex(fromIndex, outputIndexes.head))
          case _ => invalidTx(InvalidOutputGroupIndex)
        }
      }
    }

    private def getToGroup(output: TxOutput, fromIndex: GroupIndex): GroupIndex =
      output match {
        case o: AssetOutput    => o.toGroup
        case _: ContractOutput => fromIndex
      }

    protected[validation] def checkUniqueInputs(
        tx: Transaction,
        checkDoubleSpending: Boolean
    ): TxValidationResult[Unit] = {
      if (checkDoubleSpending) {
        val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
        tx.unsigned.inputs.foreachE { input =>
          if (utxoUsed.contains(input.outputRef)) {
            invalidTx(TxDoubleSpending)
          } else {
            utxoUsed += input.outputRef
            validTx(())
          }
        }
      } else {
        validTx(())
      }
    }

    protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit] = {
      EitherF.foreachTry(0 until tx.outputsLength) { outputIndex =>
        tx.getOutput(outputIndex) match {
          case output: AssetOutput =>
            if (output.additionalData.length > ALF.MaxOutputDataSize) {
              invalidTx(OutputDataSizeExceeded)
            } else {
              Right(())
            }
          case _ => Right(())
        }
      }
    }

    protected[validation] def getPreOutputs(
        tx: Transaction,
        worldState: MutableWorldState
    ): TxValidationResult[AVector[TxOutput]] = {
      worldState.getPreOutputs(tx) match {
        case Right(preOutputs)            => validTx(preOutputs)
        case Left(IOError.KeyNotFound(_)) => invalidTx(NonExistInput)
        case Left(error)                  => Left(Left(error))
      }
    }

    protected[validation] def checkLockTime(
        preOutputs: AVector[TxOutput],
        headerTs: TimeStamp
    ): TxValidationResult[Unit] = {
      if (preOutputs.forall(checkLockTime(_, headerTs))) {
        validTx(())
      } else {
        invalidTx(TimeLockedTx)
      }
    }

    @inline private def checkLockTime(output: TxOutput, headerTs: TimeStamp): Boolean =
      output match {
        case o: AssetOutput if o.lockTime > headerTs => false
        case _                                       => true
      }

    protected[validation] def checkAlfBalance(
        tx: Transaction,
        preOutputs: AVector[TxOutput],
        coinbaseNetReward: Option[U256]
    ): TxValidationResult[Unit] = {
      val inputSum = preOutputs.fold(coinbaseNetReward.getOrElse(U256.Zero))(_ addUnsafe _.amount)
      val result = for {
        outputSum <- tx.alfAmountInOutputs
        allOutSum <- outputSum.add(tx.gasFeeUnsafe) // safe after gas bound check
      } yield allOutSum == inputSum
      result match {
        case Some(true)  => validTx(())
        case Some(false) => invalidTx(InvalidAlfBalance)
        case None        => invalidTx(BalanceOverFlow)
      }
    }

    protected[validation] def checkTokenBalance(
        tx: Transaction,
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[Unit] = {
      if (tx.unsigned.scriptOpt.exists(_.entryMethod.isPayable)) {
        validTx(())
      } else {
        for {
          inputBalances  <- computeTokenBalances(preOutputs)
          outputBalances <- computeTokenBalances(tx.allOutputs)
          _ <- {
            val ok = outputBalances.forall { case (tokenId, balance) =>
              inputBalances.contains(tokenId) && inputBalances(tokenId) == balance
            }
            if (ok) validTx(()) else invalidTx(InvalidTokenBalance)
          }
        } yield ()
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    protected[validation] def computeTokenBalances(
        outputs: AVector[TxOutput]
    ): TxValidationResult[mutable.Map[TokenId, U256]] =
      try {
        val balances = mutable.Map.empty[TokenId, U256]
        outputs.foreach { output =>
          output.tokens.foreach { case (tokenId, amount) =>
            val total = balances.getOrElse(tokenId, U256.Zero)
            balances.put(tokenId, total.add(amount).get)
          }
        }
        Right(balances)
      } catch {
        case _: NoSuchElementException => Left(Right(BalanceOverFlow))
      }

    protected[validation] def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[GasBox] = {
      assume(tx.unsigned.inputs.length <= preOutputs.length)
      val signatures = Stack.unsafe(tx.inputSignatures.reverse, tx.inputSignatures.length)
      EitherF.foldTry(tx.unsigned.inputs.indices, tx.unsigned.startGas) {
        case (gasRemaining, idx) =>
          val unlockScript = tx.unsigned.inputs(idx).unlockScript
          checkLockupScript(
            tx,
            gasRemaining,
            preOutputs(idx).lockupScript,
            unlockScript,
            signatures
          )
      }
    }

    protected[validation] def checkLockupScript(
        tx: Transaction,
        gasRemaining: GasBox,
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        signatures: Stack[Signature]
    ): TxValidationResult[GasBox] = {
      (lockupScript, unlockScript) match {
        case (lock: LockupScript.P2PKH, unlock: UnlockScript.P2PKH) =>
          checkP2pkh(tx, gasRemaining, lock, unlock, signatures)
        case (lock: LockupScript.P2S, unlock: UnlockScript.P2S) =>
          checkP2S(tx, gasRemaining, lock, unlock, signatures)
        case _ =>
          invalidTx(InvalidUnlockScriptType)
      }
    }

    protected[validation] def checkP2pkh(
        tx: Transaction,
        gasRemaining: GasBox,
        lock: LockupScript.P2PKH,
        unlock: UnlockScript.P2PKH,
        signatures: Stack[Signature]
    ): TxValidationResult[GasBox] = {
      if (Hash.hash(unlock.publicKey.bytes) != lock.pkHash) {
        invalidTx(InvalidPublicKeyHash)
      } else {
        signatures.pop() match {
          case Right(signature) =>
            if (!SignatureSchema.verify(tx.id.bytes, signature, unlock.publicKey)) {
              invalidTx(InvalidSignature)
            } else {
              gasRemaining.use(GasSchedule.p2pkUnlockGas).left.map(_ => Right(OutOfGas))
            }
          case Left(_) => invalidTx(NotEnoughSignature)
        }
      }
    }

    protected[validation] def checkP2S(
        tx: Transaction,
        gasRemaining: GasBox,
        lock: LockupScript.P2S,
        unlock: UnlockScript.P2S,
        signatures: Stack[Signature]
    ): TxValidationResult[GasBox] = {
      checkScript(tx, gasRemaining, lock.script, unlock.params, signatures)
    }

    protected[validation] def checkScript(
        tx: Transaction,
        gasRemaining: GasBox,
        script: StatelessScript,
        params: AVector[Val],
        signatures: Stack[Signature]
    ): TxValidationResult[GasBox] = {
      StatelessVM.runAssetScript(tx.id, gasRemaining, script, params, signatures) match {
        case Right(result) => validTx(result.gasRemaining)
        case Left(e)       => invalidTx(InvalidUnlockScript(e))
      }
    }

    protected[validation] def checkTxScript(
        chainIndex: ChainIndex,
        tx: Transaction,
        gasRemaining: GasBox,
        worldState: WorldState.Cached
    ): TxValidationResult[Unit] = {
      if (chainIndex.isIntraGroup) {
        tx.unsigned.scriptOpt match {
          case Some(script) =>
            StatefulVM.runTxScript(worldState, tx, script, gasRemaining) match {
              case Right(StatefulVM.TxScriptExecution(_, contractInputs, generatedOutputs)) =>
                if (contractInputs != tx.contractInputs) {
                  invalidTx(InvalidContractInputs)
                } else if (generatedOutputs != tx.generatedOutputs) {
                  invalidTx(InvalidGeneratedOutputs)
                } else {
                  validTx(())
                }
              case Left(error) => invalidTx(TxScriptExeFailed(error))
            }
          case None => validTx(())
        }
      } else {
        if (tx.unsigned.scriptOpt.nonEmpty) invalidTx(UnexpectedTxScript) else validTx(())
      }
    }
  }
}
