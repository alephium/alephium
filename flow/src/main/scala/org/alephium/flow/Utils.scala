package org.alephium.flow

import org.alephium.crypto.Keccak256Hash
import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

object Utils {
  def show[T <: RandomBytes](elems: AVector[T]): String = {
    elems.map(_.shortHex).mkString("-")
  }

  def showHashable[T <: Keccak256Hash[_]](elems: AVector[T]): String = {
    elems.map(_.shortHex).mkString("-")
  }
}
