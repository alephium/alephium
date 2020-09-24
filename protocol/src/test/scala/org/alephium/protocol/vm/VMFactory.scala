package org.alephium.protocol.vm

import org.alephium.io.{MerklePatriciaTrie, StorageFixture}
import org.alephium.protocol.{Hash, Signature}

trait VMFactory extends StorageFixture {
  lazy val cachedWorldState: WorldState = {
    val db = newDB[Hash, MerklePatriciaTrie.Node]
    WorldState.emptyCached(db)
  }

  lazy val persistedWorldState: WorldState = {
    val db = newDB[Hash, MerklePatriciaTrie.Node]
    WorldState.emptyPersisted(db)
  }

  lazy val statelessContext: StatelessContext =
    StatelessContext(Hash.zero, Stack.ofCapacity[Signature](0))

  lazy val statefulContext: StatefulContext =
    StatefulContext.nonPayable(Hash.zero, cachedWorldState)
}
