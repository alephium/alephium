package org.alephium.flow

package object io {
  type IOResult[T] = Either[IOError, T]
}
