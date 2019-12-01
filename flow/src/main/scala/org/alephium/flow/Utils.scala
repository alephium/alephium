package org.alephium.flow

import org.alephium.protocol.model.FlowData
import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

object Utils {
  def show[T <: RandomBytes](elems: AVector[T]): String = {
    elems.map(_.shortHex).mkString("-")
  }

  def showHashable[T <: FlowData](elems: AVector[T]): String = {
    elems.map(_.shortHex).mkString("-")
  }
}
