package org.alephium.protocol.vm.lang

object Validator {
  case class Error(message: String) extends Exception(message)
}
