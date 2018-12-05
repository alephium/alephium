package org.alephium.serde

sealed abstract class Error extends Exception {
  final override def fillInStackTrace(): Throwable = this
}

case class InvalidNumberOfBytesException(expected: Int, got: Int) extends Error {
  override def getMessage: String = s"Number of bytes expected: $expected, got: $got"
}

case class ValidationError(message: String) extends Error

case class OtherError(message: String) extends Error
