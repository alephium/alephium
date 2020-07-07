package org.alephium

package object io {
  type IOResult[T] = Either[IOError, T]
}
