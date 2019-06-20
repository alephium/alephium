package org.alephium.protocol.model

import akka.util.ByteString
import org.alephium.serde._
import org.alephium.util.AVector

case class UnsignedTransaction(inputs: AVector[TxOutputPoint], outputs: AVector[TxOutput])

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = new Serde[UnsignedTransaction] {
    val inputsSerde: Serde[AVector[TxOutputPoint]] = Serde[AVector[TxOutputPoint]]
    val outputsSerde: Serde[AVector[TxOutput]]     = Serde[AVector[TxOutput]]

    override def serialize(input: UnsignedTransaction): ByteString = {
      inputsSerde.serialize(input.inputs) ++
        outputsSerde.serialize(input.outputs)
    }

    override def _deserialize(input: ByteString): SerdeResult[(UnsignedTransaction, ByteString)] = {
      for {
        inputsPair <- inputsSerde._deserialize(input)
        inputs = inputsPair._1
        rest1  = inputsPair._2
        outputsPair <- outputsSerde._deserialize(rest1)
        outputs = outputsPair._1
        rest2   = outputsPair._2
      } yield (UnsignedTransaction(inputs, outputs), rest2)
    }
  }
}
