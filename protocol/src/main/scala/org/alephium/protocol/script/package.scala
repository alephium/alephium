package org.alephium.protocol

package object script {
  type RunResult[T] = Either[RunFailure, T]
}
