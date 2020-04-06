package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.protocol.ALF.{Hash, HashSerde}
import org.alephium.serde._
import org.alephium.util.AVector

final case class UnsignedTransaction(inputs: AVector[TxOutputPoint],
                                     outputs: AVector[TxOutput],
                                     data: ByteString)
    extends HashSerde[UnsignedTransaction] {
  override val hash: Hash = _getHash
}

object UnsignedTransaction {
  implicit val serde: Serde[UnsignedTransaction] =
    Serde.forProduct3(UnsignedTransaction(_, _, _), t => (t.inputs, t.outputs, t.data))
}
