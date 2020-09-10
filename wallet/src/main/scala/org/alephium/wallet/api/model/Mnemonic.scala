package org.alephium.wallet.api.model

final case class Mnemonic(mnemonic: Seq[String]) {
  override def toString(): String = mnemonic.mkString(" ")
}
