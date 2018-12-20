package org.alephium.serde

// scalastyle:off null
class SerdeException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

class NotEnoughBytesException(message: String) extends SerdeException(message)

object NotEnoughBytesException {
  def apply(expected: Int, got: Int): NotEnoughBytesException =
    new NotEnoughBytesException(s"Too few bytes: expected $expected, got $got")
}

class WrongFormatException(message: String) extends SerdeException(message)

object WrongFormatException {
  def redundant(expected: Int, got: Int): WrongFormatException =
    new WrongFormatException(s"Too many bytes: expected $expected, got $got")
}

case class ValidationException(message: String) extends SerdeException(message)
