package org.alephium.protocol.model

import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.vm.{LockupScript, StatelessScript, UnlockScript, Val}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

final case class UnsignedTransaction(scriptOpt: Option[StatelessScript],
                                     inputs: AVector[TxInput],
                                     fixedOutputs: AVector[TxOutput],
                                     states: AVector[AVector[Val]])
    extends HashSerde[UnsignedTransaction] {
  override lazy val hash: Hash = _getHash
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct4(UnsignedTransaction.apply,
                      t => (t.scriptOpt, t.inputs, t.fixedOutputs, t.states))

  def apply(inputs: AVector[TxInput], fixedOutputs: AVector[TxOutput]): UnsignedTransaction = {
    UnsignedTransaction(None, inputs, fixedOutputs, AVector.empty)
  }

  def transferAlf(inputs: AVector[TxOutputRef],
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
      if (remainder > U64.Zero) AVector[TxOutput](toOutput, fromOutput)
      else AVector[TxOutput](toOutput)
    UnsignedTransaction(inputs.map(TxInput(_, fromUnlockScript)), outputs)
  }
}
