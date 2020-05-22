package org.alephium.protocol

import org.alephium.util.AVector

package object script {
  type RunResult[T] = Either[RunFailure, T]
  type Script       = AVector[Instruction]
}
