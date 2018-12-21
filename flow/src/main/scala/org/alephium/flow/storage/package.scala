package org.alephium.flow

package object storage {
  type IOResult[T] = Either[IOError, T]
}
