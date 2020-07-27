package org.alephium.protocol.vm

import org.alephium.crypto.ED25519Signature
import org.alephium.io.{MerklePatriciaTrie, StorageFixture}
import org.alephium.protocol.ALF

trait VMFactory extends StorageFixture {
  lazy val cachedWorldState: WorldState = {
    val db = newDB[ALF.Hash, MerklePatriciaTrie.Node]
    WorldState.emptyCached(db)
  }

  lazy val persistedWorldState: WorldState = {
    val db = newDB[ALF.Hash, MerklePatriciaTrie.Node]
    WorldState.emptyPersisted(db)
  }

  lazy val statelessContext: StatelessContext =
    StatelessContext(ALF.Hash.zero, Stack.ofCapacity[ED25519Signature](0), cachedWorldState)

  lazy val statefulContext: StatefulContext = StatefulContext(ALF.Hash.zero, cachedWorldState)
}
