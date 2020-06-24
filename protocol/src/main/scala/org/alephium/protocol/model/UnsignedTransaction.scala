package org.alephium.protocol.model

import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.protocol.script.{Instruction, PubScript, Script}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

final case class UnsignedTransaction(script: Option[Script],
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
                  fromPubScript: PubScript,
                  fromPriScript: AVector[Instruction],
                  toPubScript: PubScript,
                  amount: U64,
                  height: Int): UnsignedTransaction = {
    assume(inputSum >= amount)
    val remainder = inputSum.subUnsafe(amount)

    val toOutput   = TxOutput.build(amount, height, toPubScript)
    val fromOutput = TxOutput.build(remainder, height, fromPubScript)

    val outputs =
      if (remainder > U64.Zero) AVector[TxOutput](toOutput, fromOutput)
      else AVector[TxOutput](toOutput)
    UnsignedTransaction(None, inputs.map(TxInput(_, fromPriScript)), outputs)
  }
}
