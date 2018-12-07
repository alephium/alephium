package org.alephium.serde

// scalastyle:off null
class SerdeException(message: String, cause: Throwable) extends RuntimeException(message, cause) {
  def this(_message: String) = this(_message, null)
}

class InvalidNumberOfBytesException(expected: Int, got: Int, cause: Throwable)
    extends SerdeException(s"Number of bytes expected: $expected, got: $got", cause) {
  def this(_expected: Int, _got: Int) = this(_expected, _got, null)
}

class WrongFormatException(message: String, cause: Throwable)
    extends SerdeException(message, cause) {
  def this(_message: String) = this(_message, null)
}

case class OtherException(message: String, cause: Throwable)
    extends SerdeException(message, cause) {
  def this(_message: String) = this(_message, null)
}
