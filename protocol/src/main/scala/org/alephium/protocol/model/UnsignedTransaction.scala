package org.alephium.protocol.model

import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.vm.{LockupScript, StatefulScript, UnlockScript}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

final case class UnsignedTransaction(script: Option[StatefulScript],
                                     inputs: AVector[TxInput],
                                     fixedOutputs: AVector[TxOutput])
    extends HashSerde[UnsignedTransaction] {
  override lazy val hash: Hash = _getHash
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct3(UnsignedTransaction(_, _, _), t => (t.script, t.inputs, t.fixedOutputs))

  def transferAlf(inputs: AVector[TxOutputRef],
                  inputSum: U64,
                  fromLockupScript: LockupScript,
                  fromUnlockScript: UnlockScript,
                  toLockupScript: LockupScript,
                  amount: U64,
                  height: Int): UnsignedTransaction = {
    assume(inputSum >= amount)
    val remainder = inputSum.subUnsafe(amount)

    val toOutput   = TxOutput.build(amount, height, toLockupScript)
    val fromOutput = TxOutput.build(remainder, height, fromLockupScript)

    val outputs =
      if (remainder > U64.Zero) AVector[TxOutput](toOutput, fromOutput)
      else AVector[TxOutput](toOutput)
    UnsignedTransaction(None, inputs.map(TxInput(_, fromUnlockScript)), outputs)
  }
}
