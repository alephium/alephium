package org.alephium.flow.validation

import scala.collection.mutable

import org.alephium.flow.core.BlockFlow
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.{ALF, Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.serde.serialize
import org.alephium.util.{AVector, EitherF, U64}

trait NonCoinbaseValidation {
  import ValidationStatus._

  implicit def groupConfig: GroupConfig

  def validateMempoolTx(tx: Transaction, flow: BlockFlow): IOResult[TxStatus] = {
    val validationResult = for {
      _          <- checkStateless(tx)
      worldState <- from(flow.getBestPersistedTrie(tx.chainIndex.from))
      _          <- checkStateful(tx, worldState)
    } yield ()
    convert(validationResult, ValidTx)
  }
  protected[validation] def checkBlockTx(tx: Transaction,
                                         worldState: WorldState): TxValidationResult[Unit] = {
    for {
      _ <- checkStateless(tx)
      _ <- checkStateful(tx, worldState)
    } yield ()
  }

  protected[validation] def checkStateless(tx: Transaction): TxValidationResult[ChainIndex] = {
    for {
      _          <- checkInputNum(tx)
      _          <- checkOutputNum(tx)
      _          <- checkAlfOutputAmount(tx)
      chainIndex <- checkChainIndex(tx)
      _          <- checkUniqueInputs(tx)
      _          <- checkOutputDataSize(tx)
    } yield chainIndex
  }
  protected[validation] def checkStateful(tx: Transaction,
                                          worldState: WorldState): TxValidationResult[Unit] = {
    for {
      preOutputs <- getPreOutputs(tx, worldState)
      _          <- checkAlfBalance(tx, preOutputs)
      _          <- checkTokenBalance(tx, preOutputs)
      _          <- checkWitnesses(tx, preOutputs)
    } yield ()
  }

  protected[validation] def getPreOutputs(
      tx: Transaction,
      worldState: WorldState): TxValidationResult[AVector[TxOutput]]

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkOutputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkAlfOutputAmount(tx: Transaction): TxValidationResult[U64]
  protected[validation] def checkChainIndex(tx: Transaction): TxValidationResult[ChainIndex]
  protected[validation] def checkUniqueInputs(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit]

  protected[validation] def checkAlfBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkTokenBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkWitnesses(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  // format: on
}

// Note: only non-coinbase transactions are validated here
object NonCoinbaseValidation {
  import ValidationStatus._

  def build(implicit groupConfig: GroupConfig): NonCoinbaseValidation = new Impl()

  class Impl(implicit val groupConfig: GroupConfig) extends NonCoinbaseValidation {
    protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit] = {
      val inputNum = tx.unsigned.inputs.length
      if (inputNum == 0) invalidTx(NoInputs)
      else if (inputNum > ALF.MaxTxInputNum) invalidTx(TooManyInputs)
      else validTx(())
    }

    protected[validation] def checkOutputNum(tx: Transaction): TxValidationResult[Unit] = {
      val outputNum = tx.outputsLength
      if (outputNum == 0) invalidTx(NoOutputs)
      else if (outputNum > ALF.MaxTxOutputNum) invalidTx(TooManyOutputs)
      else validTx(())
    }

    protected[validation] def checkAlfOutputAmount(tx: Transaction): TxValidationResult[U64] = {
      tx.alfAmountInOutputs match {
        case Some(total) => validTx(total)
        case None        => invalidTx(BalanceOverFlow)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    protected[validation] def checkChainIndex(tx: Transaction): TxValidationResult[ChainIndex] = {
      val inputIndexes = tx.unsigned.inputs.map(_.fromGroup).toSet
      if (inputIndexes.size != 1) invalidTx(InvalidInputGroupIndex)
      else {
        val fromIndex = inputIndexes.head
        val outputIndexes =
          (0 until tx.outputsLength).view.map(tx.getOutput(_).toGroup).filter(_ != fromIndex).toSet
        outputIndexes.size match {
          case 0 => validTx(ChainIndex(fromIndex, fromIndex))
          case 1 => validTx(ChainIndex(fromIndex, outputIndexes.head))
          case _ => invalidTx(InvalidOutputGroupIndex)
        }
      }
    }

    protected[validation] def checkUniqueInputs(tx: Transaction): TxValidationResult[Unit] = {
      val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) invalidTx(DoubleSpending)
        else {
          utxoUsed += input.outputRef
          validTx(())
        }
      }
    }

    protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit] = {
      EitherF.foreachTry(0 until tx.outputsLength) { outputIndex =>
        val output = tx.getOutput(outputIndex)
        if (output.additionalData.length > ALF.MaxOutputDataSize) invalidTx(OutputDataSizeExceeded)
        else Right(())
      }
    }

    protected[validation] def getPreOutputs(
        tx: Transaction,
        worldState: WorldState): TxValidationResult[AVector[TxOutput]] = {
      worldState.getPreOutputs(tx) match {
        case Right(preOutputs)            => validTx(preOutputs)
        case Left(IOError.KeyNotFound(_)) => invalidTx(NonExistInput)
        case Left(error)                  => Left(Left(error))
      }
    }

    protected[validation] def checkAlfBalance(
        tx: Transaction,
        preOutputs: AVector[TxOutput]): TxValidationResult[Unit] = {
      val inputSum = preOutputs.fold(U64.Zero)(_ addUnsafe _.amount)
      tx.alfAmountInOutputs match {
        case Some(outputSum) if outputSum <= inputSum => validTx(())
        case Some(_)                                  => invalidTx(InvalidAlfBalance)
        case None                                     => invalidTx(BalanceOverFlow)
      }
    }

    protected[validation] def checkTokenBalance(
        tx: Transaction,
        preOutputs: AVector[TxOutput]): TxValidationResult[Unit] = {
      for {
        inputBalances  <- computeTokenBalances(preOutputs)
        outputBalances <- computeTokenBalances(tx.allOutputs)
        _ <- {
          val ok = outputBalances.forall {
            case (tokenId, balance) =>
              (inputBalances.contains(tokenId) && inputBalances(tokenId) >= balance) || tokenId == tx.newTokenId
          }
          if (ok) validTx(()) else invalidTx(InvalidTokenBalance)
        }
      } yield ()
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    protected[validation] def computeTokenBalances(
        outputs: AVector[TxOutput]): TxValidationResult[mutable.Map[TokenId, U64]] =
      try {
        val balances = mutable.Map.empty[TokenId, U64]
        outputs.foreach { output =>
          output.tokens.foreach {
            case (tokenId, amount) =>
              val total = balances.getOrElse(tokenId, U64.Zero)
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
        preOutputs: AVector[TxOutput]): TxValidationResult[Unit] = {
      assume(tx.unsigned.inputs.length == preOutputs.length)
      val signatures = Stack.unsafe(tx.signatures.reverse, tx.signatures.length)
      EitherF.foreachTry(preOutputs.indices) { idx =>
        val unlockScript = tx.unsigned.inputs(idx).unlockScript
        checkLockupScript(tx, preOutputs(idx).lockupScript, unlockScript, signatures)
      }
    }

    protected[validation] def checkLockupScript(
        tx: Transaction,
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        signatures: Stack[Signature]): TxValidationResult[Unit] = {
      (lockupScript, unlockScript) match {
        case (lock: LockupScript.P2PKH, unlock: UnlockScript.P2PKH) =>
          checkP2pkh(tx, lock, unlock, signatures)
        case (lock: LockupScript.P2SH, unlock: UnlockScript.P2SH) =>
          checkP2SH(tx, lock, unlock, signatures)
        case (lock: LockupScript.P2S, unlock: UnlockScript.P2S) =>
          checkP2S(tx, lock, unlock, signatures)
        case _ =>
          invalidTx(InvalidUnlockScriptType)
      }
    }

    protected[validation] def checkP2pkh(tx: Transaction,
                                         lock: LockupScript.P2PKH,
                                         unlock: UnlockScript.P2PKH,
                                         signatures: Stack[Signature]): TxValidationResult[Unit] = {
      if (Hash.hash(unlock.publicKey.bytes) != lock.pkHash) {
        invalidTx(InvalidPublicKeyHash)
      } else {
        signatures.pop() match {
          case Right(signature) =>
            if (!SignatureSchema.verify(tx.hash.bytes, signature, unlock.publicKey)) {
              invalidTx(InvalidSignature)
            } else validTx(())
          case Left(_) => invalidTx(NotEnoughSignature)
        }
      }
    }

    protected[validation] def checkP2SH(tx: Transaction,
                                        lock: LockupScript.P2SH,
                                        unlock: UnlockScript.P2SH,
                                        signatures: Stack[Signature]): TxValidationResult[Unit] = {
      if (Hash.hash(serialize(unlock.script)) != lock.scriptHash) {
        invalidTx(InvalidScriptHash)
      } else {
        checkScript(tx, unlock.script, unlock.params, signatures)
      }
    }

    protected[validation] def checkP2S(tx: Transaction,
                                       lock: LockupScript.P2S,
                                       unlock: UnlockScript.P2S,
                                       signatures: Stack[Signature]): TxValidationResult[Unit] = {
      checkScript(tx, lock.script, unlock.params, signatures)
    }

    protected[validation] def checkScript(
        tx: Transaction,
        script: StatelessScript,
        params: AVector[Val],
        signatures: Stack[Signature]): TxValidationResult[Unit] = {
      StatelessVM.runAssetScript(tx.hash, script, params, signatures) match {
        case Right(_) => validTx(()) // TODO: handle returns
        case Left(e)  => invalidTx(InvalidUnlockScript(e))
      }
    }
  }
}
