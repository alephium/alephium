package org.alephium.protocol.vm

import org.alephium.crypto.ED25519Signature
import org.alephium.io.{MerklePatriciaTrie, StorageFixture}
import org.alephium.protocol.Hash

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
    StatelessContext(Hash.zero, Stack.ofCapacity[ED25519Signature](0), cachedWorldState)

  lazy val statefulContext: StatefulContext = StatefulContext(Hash.zero, cachedWorldState)
}
