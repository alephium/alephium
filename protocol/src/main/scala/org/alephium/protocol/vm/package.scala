package org.alephium.protocol

package object vm {
  type ExeResult[T] = Either[ExeFailure, T]

  val stackMaxSize: Int = 0xFF
}
