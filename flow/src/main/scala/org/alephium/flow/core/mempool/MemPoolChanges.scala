package org.alephium.flow.core.mempool

import org.alephium.protocol.model.Transaction
import org.alephium.util.AVector

trait MemPoolChanges
final case class Normal(toRemove: AVector[AVector[Transaction]]) extends MemPoolChanges
final case class Reorg(toRemove: AVector[AVector[Transaction]],
                       toAdd: AVector[AVector[(Transaction, Double)]])
    extends MemPoolChanges
