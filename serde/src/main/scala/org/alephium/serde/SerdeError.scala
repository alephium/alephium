package org.alephium.serde

sealed abstract class SerdeError(message: String) extends Exception(message)

object SerdeError {
  case class NotEnoughBytes private (message: String) extends SerdeError(message)
  case class WrongFormat private (message: String)    extends SerdeError(message)
  case class Validation private (message: String)     extends SerdeError(message)

  def notEnoughBytes(expected: Int, got: Int): NotEnoughBytes =
    NotEnoughBytes(s"Too few bytes: expected $expected, got $got")

  def redundant(expected: Int, got: Int): WrongFormat =
    WrongFormat(s"Too many bytes: expected $expected, got $got")

  def validation(message: String): Validation   = Validation(message)
  def wrongFormat(message: String): WrongFormat = WrongFormat(message)
}
