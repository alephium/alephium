package org.alephium.protocol.model

import org.alephium.protocol.script.Script.PubScript
import org.alephium.serde.Serde

case class TxOutput(value: BigInt, pubScript: PubScript) {
  def shortKey: Long = ???
}

object TxOutput {
  implicit val serde: Serde[TxOutput] = Serde.forProduct2(apply, to => (to.value, to.pubScript))
}
