package org.alephium.flow.model

import org.alephium.serde.Serde

final case class BlockState(height: Int, weight: BigInt)

object BlockState {
  implicit val serde: Serde[BlockState] =
    Serde.forProduct2(BlockState(_, _), t => (t.height, t.weight))
}
