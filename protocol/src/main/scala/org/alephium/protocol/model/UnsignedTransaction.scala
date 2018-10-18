package org.alephium.protocol.model

import akka.util.ByteString
import org.alephium.serde.Serde
import org.alephium.util.AVector

import scala.util.Try

case class UnsignedTransaction(inputs: AVector[TxInput], outputs: AVector[TxOutput])

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = new Serde[UnsignedTransaction] {
    val inputsSerde: Serde[AVector[TxInput]]   = Serde[AVector[TxInput]]
    val outputsSerde: Serde[AVector[TxOutput]] = Serde[AVector[TxOutput]]

    override def serialize(input: UnsignedTransaction): ByteString = {
      inputsSerde.serialize(input.inputs) ++
        outputsSerde.serialize(input.outputs)
    }

    override def _deserialize(input: ByteString): Try[(UnsignedTransaction, ByteString)] = {
      for {
        (inputs, rest1)  <- inputsSerde._deserialize(input)
        (outputs, rest2) <- outputsSerde._deserialize(rest1)
      } yield (UnsignedTransaction(inputs, outputs), rest2)
    }
  }
}
