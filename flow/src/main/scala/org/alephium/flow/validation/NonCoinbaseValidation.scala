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

import scala.annotation.tailrec
import scala.collection.mutable

import org.alephium.flow.core.{BlockFlow, FlowUtils}
import org.alephium.io.IOError
import org.alephium.protocol.{ALF, Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{OutOfGas => _, _}
import org.alephium.util.{AVector, EitherF, TimeStamp, U256}

trait NonCoinbaseValidation {
  import ValidationStatus._

  implicit def groupConfig: GroupConfig

  def validateMempoolTxTemplate(
      tx: TransactionTemplate,
      flow: BlockFlow
  ): TxValidationResult[Unit] = {
    tx.unsigned.scriptOpt match {
      case None =>
        val fullTx = FlowUtils.convertNonScriptTx(tx)
        validateMempoolTx(fullTx, flow)
      case Some(script) =>
        for {
          chainIndex <- checkChainIndex(tx)
          worldState <- from(flow.getBestCachedWorldState(chainIndex.from))
          fullTx <- StatefulVM.runTxScript(worldState, tx, script, tx.unsigned.startGas) match {
            case Left(error)   => invalidTx(TxScriptExeFailed(error))
            case Right(result) => validTx(FlowUtils.convertSuccessfulTx(tx, result))
          }
          _ <- validateMempoolTx(fullTx, flow)
        } yield ()
    }
  }

  def validateMempoolTx(tx: Transaction, flow: BlockFlow): TxValidationResult[Unit] = {
    for {
      _          <- checkStateless(tx, checkDoubleSpending = true)
      worldState <- from(flow.getBestCachedWorldState(tx.chainIndex.from))
      _          <- checkStateful(tx, TimeStamp.now(), worldState)
    } yield ()
  }

  protected[validation] def checkBlockTx(
      tx: Transaction,
      header: BlockHeader,
      worldState: WorldState.Cached
  ): TxValidationResult[Unit] = {
    for {
      _ <- checkStateless(
        tx,
        checkDoubleSpending = false
      ) // it's already checked in BlockValidation
      _ <- checkStateful(tx, header.timestamp, worldState)
    } yield ()
  }

  protected[validation] def checkStateless(
      tx: Transaction,
      checkDoubleSpending: Boolean
  ): TxValidationResult[ChainIndex] = {
    for {
      _          <- checkInputNum(tx)
      _          <- checkOutputNum(tx)
      _          <- checkGasBound(tx)
      _          <- checkOutputAmount(tx)
      chainIndex <- checkChainIndex(tx)
      _          <- checkUniqueInputs(tx, checkDoubleSpending)
      _          <- checkOutputDataSize(tx)
    } yield chainIndex
  }
  protected[validation] def checkStateful(
      tx: Transaction,
      headerTs: TimeStamp,
      worldState: WorldState.Cached
  ): TxValidationResult[Unit] = {
    for {
      preOutputs   <- getPreOutputs(tx, worldState)
      _            <- checkLockTime(preOutputs, headerTs)
      _            <- checkAlfBalance(tx, preOutputs)
      _            <- checkTokenBalance(tx, preOutputs)
      gasRemaining <- checkWitnesses(tx, preOutputs)
      _            <- checkTxScript(tx, gasRemaining, worldState)
    } yield ()
  }

  protected[validation] def getPreOutputs(
      tx: Transaction,
      worldState: MutableWorldState
  ): TxValidationResult[AVector[TxOutput]]

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkOutputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkGasBound(tx: TransactionAbstract): TxValidationResult[Unit]
  protected[validation] def checkOutputAmount(tx: Transaction): TxValidationResult[U256]
  protected[validation] def checkChainIndex(tx: TransactionAbstract): TxValidationResult[ChainIndex]
  protected[validation] def checkUniqueInputs(tx: Transaction, checkDoubleSpending: Boolean): TxValidationResult[Unit]
  protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit]

  protected[validation] def checkLockTime(preOutputs: AVector[TxOutput], headerTs: TimeStamp): TxValidationResult[Unit]
  protected[validation] def checkAlfBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkTokenBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkWitnesses(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[GasBox]
  protected[validation] def checkTxScript(
      tx: Transaction,
      gasRemaining: GasBox,
      worldState: WorldState.Cached): TxValidationResult[Unit] // TODO: optimize it with preOutputs
  // format: on
}

// Note: only non-coinbase transactions are validated here
object NonCoinbaseValidation {
  import ValidationStatus._

  def build(implicit groupConfig: GroupConfig): NonCoinbaseValidation = new Impl()

  class Impl(implicit val groupConfig: GroupConfig) extends NonCoinbaseValidation {
    protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit] = {
      val inputNum = tx.unsigned.inputs.length
      if (inputNum == 0) {
        invalidTx(NoInputs)
      } else if (inputNum > ALF.MaxTxInputNum) {
        invalidTx(TooManyInputs)
      } else {
        validTx(())
      }
    }

    protected[validation] def checkOutputNum(tx: Transaction): TxValidationResult[Unit] = {
      val outputNum = tx.outputsLength
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

    private def checkGasPrice(gas: U256): Boolean = {
      gas > U256.Zero && gas < ALF.MaxALFValue
    }

    protected[validation] def checkOutputAmount(tx: Transaction): TxValidationResult[U256] = {
      for {
        _      <- checkPositiveOutputAmount(tx)
        amount <- checkAlfOutputAmount(tx)
      } yield amount
    }

    protected[validation] def checkPositiveOutputAmount(
        tx: Transaction
    ): TxValidationResult[Unit] = {
      @tailrec
      def iter(outputIndex: Int): TxValidationResult[Unit] = {
        if (outputIndex >= tx.outputsLength) {
          validTx(())
        } else {
          val output = tx.getOutput(outputIndex)
          val ok     = output.amount.nonZero && output.tokens.forall(_._2.nonZero)
          if (ok) iter(outputIndex + 1) else invalidTx(AmountIsZero)
        }
      }

      iter(0)
    }

    protected[validation] def checkAlfOutputAmount(tx: Transaction): TxValidationResult[U256] = {
      tx.alfAmountInOutputs match {
        case Some(total) => validTx(total)
        case None        => invalidTx(BalanceOverFlow)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    protected[validation] def checkChainIndex(
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
        preOutputs: AVector[TxOutput]
    ): TxValidationResult[Unit] = {
      val inputSum = preOutputs.fold(U256.Zero)(_ addUnsafe _.amount)
      val result = for {
        outputSum <- tx.alfAmountInOutputs
        allOutSum <- outputSum.add(tx.gasFeeUnsafe) // safe after gas bound check
      } yield allOutSum <= inputSum
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
              (inputBalances.contains(tokenId) && inputBalances(tokenId) >= balance)
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

    // TODO: signatures might not be 1-to-1 mapped to inputs
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
        tx: Transaction,
        gasRemaining: GasBox,
        worldState: WorldState.Cached
    ): TxValidationResult[Unit] = {
      val chainIndex = tx.chainIndex
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
