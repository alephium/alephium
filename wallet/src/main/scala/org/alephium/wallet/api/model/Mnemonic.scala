package org.alephium.wallet.api.model

import org.alephium.util.AVector

final case class Mnemonic(mnemonic: AVector[String]) {
  override def toString(): String = mnemonic.mkString(" ")
}
