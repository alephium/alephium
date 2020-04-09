package org.alephium.flow.model

import org.alephium.serde.Serde

final case class BlockState(height: Int, weight: BigInt, chainWeight: BigInt)

object BlockState {
  implicit val serde: Serde[BlockState] =
    Serde.forProduct3(BlockState(_, _, _), t => (t.height, t.weight, t.chainWeight))
}
