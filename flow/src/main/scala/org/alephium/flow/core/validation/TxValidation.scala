package org.alephium.flow.core.validation

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.platform.PlatformConfig
import org.alephium.io.IOResult
import org.alephium.protocol.ALF
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.Transaction
import org.alephium.util.U64

// Note: only non-coinbase transactions are validated here
object TxValidation {
  import ValidationStatus._

  def validateNonCoinbase(tx: Transaction, flow: BlockFlow)(
      implicit config: PlatformConfig): IOResult[TxStatus] = {
    convert(Validation.validateNonCoinbaseTx(tx, flow), ValidTx)
  }

  def validateStateless(tx: Transaction)(implicit config: GroupConfig): IOResult[TxStatus] = {
    convert(validateStatelessInternal(tx), ValidTx)
  }

  def validateStatelessInternal(tx: Transaction)(
      implicit config: GroupConfig): TxValidationResult[Unit] = {
    for {
      _ <- validateInputNum(tx)
      _ <- validateOutputNum(tx)
      _ <- validateAlfOutputAmount(tx)
      _ <- validateUniqueInputs(tx)
      _ <- validateChainIndex(tx)
    } yield ()
  }

  def validateInputNum(tx: Transaction): TxValidationResult[Unit] = {
    val inputNum = tx.unsigned.inputs.length
    if (inputNum == 0) invalidTx(EmptyInputs)
    else if (inputNum > ALF.MaxTxInputNum) invalidTx(TooManyInputs)
    else validTx(())
  }

  def validateOutputNum(tx: Transaction): TxValidationResult[Unit] = {
    val outputNum = tx.outputsLength
    if (outputNum == 0) invalidTx(EmptyOutputs)
    else if (outputNum > ALF.MaxTxOutputNum) invalidTx(TooManyOutputs)
    else validTx(())
  }

  def validateAlfOutputAmount(tx: Transaction): TxValidationResult[U64] = {
    tx.alfAmountInOutputs match {
      case Some(total) => validTx(total)
      case None        => invalidTx(BalanceOverFlow)
    }
  }

  def validateUniqueInputs(tx: Transaction): TxValidationResult[Unit] = {
    val inputs = tx.unsigned.inputs
    if (inputs.toSet.size != inputs.length) invalidTx(DuplicatedInputs)
    else validTx(())
  }

  def validateChainIndex(tx: Transaction)(
      implicit config: GroupConfig): TxValidationResult[Unit] = {
    val inputIndexes = tx.unsigned.inputs.map(_.fromGroup).toSet
    if (inputIndexes.size != 1) invalidTx(InvalidChainIndex)
    else validTx(()) // TODO: fix this after make contract output indexable
  }
}
