package org.alephium.protocol.model

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.vm.{LockupScript, StatefulScript, UnlockScript, Val}
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

/**
  * Upto one new token might be issued in each transaction exception for the coinbase transaction
  * The id of the new token will be hash of the first input
  *
  * @param scriptOpt optional script for invoking stateful contracts
  * @param inputs a vector of TxInput
  * @param fixedOutputs a vector of TxOutput. ContractOutput are put in front of AssetOutput
  * @param states a vector of contract states, each one of which is a vector of vm Val
  */
final case class UnsignedTransaction(scriptOpt: Option[StatefulScript],
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
