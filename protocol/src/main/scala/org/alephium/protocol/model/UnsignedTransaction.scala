package org.alephium.protocol.model

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, StatefulScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

/**
  * Upto one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param scriptOpt optional script for invoking stateful contracts
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  */
final case class UnsignedTransaction(scriptOpt: Option[StatefulScript],
                                     inputs: AVector[TxInput],
                                     fixedOutputs: AVector[AssetOutput])
    extends HashSerde[UnsignedTransaction] {
  override lazy val hash: Hash = _getHash

  // this might only works for validated tx
  def fromGroup(implicit config: GroupConfig): GroupIndex = {
    inputs.head.fromGroup
  }

  // this might only works for validated tx
  def toGroup(implicit config: GroupConfig): GroupIndex = {
    val from    = fromGroup
    val outputs = fixedOutputs
    if (outputs.isEmpty) from
    else {
      val index = outputs.indexWhere(_.toGroup != from)
      if (index == -1) from
      else outputs(index).toGroup
    }
  }

  // this might only works for validated tx
  def chainIndex(implicit config: GroupConfig): ChainIndex = ChainIndex(fromGroup, toGroup)
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct3(UnsignedTransaction.apply, t => (t.scriptOpt, t.inputs, t.fixedOutputs))

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[AssetOutput]): UnsignedTransaction = {
    UnsignedTransaction(None, inputs, fixedOutputs)
  }

  def transferAlf(inputs: AVector[AssetOutputRef],
                  inputSum: U64,
                  fromLockupScript: LockupScript,
                  fromUnlockScript: UnlockScript,
                  toLockupScript: LockupScript,
                  amount: U64,
                  height: Int): UnsignedTransaction = {
    assume(inputSum >= amount)
    val remainder = inputSum.subUnsafe(amount)

    val toOutput   = TxOutput.asset(amount, height, toLockupScript)
    val fromOutput = TxOutput.asset(remainder, height, fromLockupScript)

    val outputs =
      if (remainder > U64.Zero) AVector[AssetOutput](toOutput, fromOutput)
      else AVector[AssetOutput](toOutput)
    UnsignedTransaction(inputs.map(TxInput(_, fromUnlockScript)), outputs)
  }
}
