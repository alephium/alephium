package org.alephium.protocol

package object io {
  type IOResult[T] = Either[IOError, T]
}
