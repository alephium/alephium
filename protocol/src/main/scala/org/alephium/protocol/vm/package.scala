package org.alephium.protocol

package object vm {
  type ExeResult[T] = Either[ExeFailure, T]

  val opStackMaxSize: Int    = 0xFF
  val frameStackMaxSize: Int = 0xFF
}
