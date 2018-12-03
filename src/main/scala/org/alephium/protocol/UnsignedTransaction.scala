package org.alephium.protocol

import akka.util.ByteString
import org.alephium.serde._

import scala.util.Try

case class UnsignedTransaction(inputs: Seq[TxInput], outputs: Seq[TxOutput])

object UnsignedTransaction {
  // Scala implicits lookup sucks here, intellij works however
  implicit val serde: Serde[UnsignedTransaction] = new Serde[UnsignedTransaction] {
    override def serialize(input: UnsignedTransaction): ByteString = {
      implicitly[Serde[Seq[TxInput]]].serialize(input.inputs) ++
        implicitly[Serde[Seq[TxOutput]]].serialize(input.outputs)
    }

    override def _deserialize(input: ByteString): Try[(UnsignedTransaction, ByteString)] = {
      for {
        (inputs, rest1)  <- implicitly[Serde[Seq[TxInput]]]._deserialize(input)
        (outputs, rest2) <- implicitly[Serde[Seq[TxOutput]]]._deserialize(rest1)
      } yield (UnsignedTransaction(inputs, outputs), rest2)
    }
  }
}
