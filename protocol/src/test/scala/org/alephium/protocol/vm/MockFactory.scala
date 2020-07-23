package org.alephium.protocol.vm

import org.alephium.crypto.ED25519Signature
import org.alephium.io.{MerklePatriciaTrie, MockFactory => IOMockFactory}
import org.alephium.protocol.ALF
import org.alephium.protocol.model.{TxOutput, TxOutputRef}
import org.alephium.protocol.vm.WorldState.{Cached, Persisted}

trait MockFactory extends IOMockFactory {
  lazy val mockWorldState: WorldState = {
    val outputState: MerklePatriciaTrie[TxOutputRef, TxOutput] =
      MerklePatriciaTrie(ALF.Hash.zero, unimplementedStorage[ALF.Hash, MerklePatriciaTrie.Node])
    val contractState: MerklePatriciaTrie[ALF.Hash, ContractState] =
      MerklePatriciaTrie(ALF.Hash.zero, unimplementedStorage[ALF.Hash, MerklePatriciaTrie.Node])
    Cached(Persisted(outputState, contractState), Set.empty, Map.empty, Map.empty)
  }

  lazy val mockStatelessContext: StatelessContext =
    StatelessContext(ALF.Hash.zero, Stack.ofCapacity[ED25519Signature](0), mockWorldState)

  lazy val mockStatefulContext: StatefulContext = StatefulContext(ALF.Hash.zero, mockWorldState)
}
