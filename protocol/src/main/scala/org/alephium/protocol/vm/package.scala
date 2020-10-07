package org.alephium.protocol

package object vm {
  type ExeResult[T] = Either[ExeFailure, T]

  val opStackMaxSize: Int    = 0x400
  val frameStackMaxSize: Int = 0x400
}
