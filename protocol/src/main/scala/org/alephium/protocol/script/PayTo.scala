package org.alephium.protocol.script

sealed trait PayTo extends scala.Product {
  val name: String              = this.productPrefix.toLowerCase
  override def toString: String = name
}
object PayTo {
  case object PKH extends PayTo
  case object SH  extends PayTo
}
