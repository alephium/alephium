package org.alephium.flow

package object storage {
  type DBResult[T] = Either[DBError, T]
}
