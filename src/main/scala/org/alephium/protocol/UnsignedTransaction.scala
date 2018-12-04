package org.alephium.protocol

import akka.util.ByteString
import org.alephium.serde.Serde

import scala.util.Try

case class UnsignedTransaction(inputs: Seq[TxInput], outputs: Seq[TxOutput])

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] = new Serde[UnsignedTransaction] {
    val inputsSerde: Serde[Seq[TxInput]] = implicitly[Serde[Seq[TxInput]]]
    val outputsSerde: Serde[Seq[TxOutput]] = implicitly[Serde[Seq[TxOutput]]]
    override def serialize(input: UnsignedTransaction): ByteString = {
      inputsSerde.serialize(input.inputs) ++
        outputsSerde.serialize(input.outputs)
    }

    override def _deserialize(input: ByteString): Try[(UnsignedTransaction, ByteString)] = {
      for {
        (inputs, rest1) <- inputsSerde._deserialize(input)
        (outputs, rest2) <- outputsSerde._deserialize(rest1)
      } yield (UnsignedTransaction(inputs, outputs), rest2)
    }
  }
}
