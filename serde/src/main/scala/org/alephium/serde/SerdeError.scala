package org.alephium.serde

sealed trait SerdeError extends Exception {
  def message: String
}

case class NotEnoughBytesError(message: String) extends SerdeError

object NotEnoughBytesError {
  def apply(expected: Int, got: Int): NotEnoughBytesError =
    new NotEnoughBytesError(s"Too few bytes: expected $expected, got $got")
}

case class WrongFormatError(message: String) extends SerdeError

object WrongFormatError {
  def redundant(expected: Int, got: Int): WrongFormatError =
    new WrongFormatError(s"Too many bytes: expected $expected, got $got")
}

case class ValidationError(message: String) extends SerdeError
