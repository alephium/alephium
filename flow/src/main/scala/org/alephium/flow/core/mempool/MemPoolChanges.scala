package org.alephium.flow.core.mempool

import org.alephium.protocol.model.Transaction
import org.alephium.util.AVector

trait MemPoolChanges
case class Normal(toRemove: AVector[AVector[Transaction]]) extends MemPoolChanges
case class Reorg(toRemove: AVector[AVector[Transaction]],
                 toAdd: AVector[AVector[(Transaction, Double)]])
    extends MemPoolChanges
