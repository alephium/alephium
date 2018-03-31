package org.alephium.serde

sealed abstract class Error extends Exception {
  final override def fillInStackTrace(): Throwable = this
}

final case class InvalidNumberOfBytesException(expected: Int, got: Int) extends Error {
  override def getMessage: String = s"Number of bytes expected: $expected, got: $got"
}

final case class OtherError(message: String) extends Error
