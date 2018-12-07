package org.alephium.serde

// scalastyle:off null
class SerdeError(message: String, cause: Throwable) extends RuntimeException(message, cause) {
  def this(_message: String) = this(_message, null)
}

class InvalidNumberOfBytesException(expected: Int, got: Int, cause: Throwable)
    extends SerdeError(s"Number of bytes expected: $expected, got: $got", cause) {
  def this(_expected: Int, _got: Int) = this(_expected, _got, null)
}

class ValidationError(message: String, cause: Throwable) extends SerdeError(message, cause) {
  def this(_message: String) = this(_message, null)
}

case class OtherError(message: String, cause: Throwable) extends SerdeError(message, cause) {
  def this(_message: String) = this(_message, null)
}
