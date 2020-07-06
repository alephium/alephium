package org.alephium.flow.trie

import org.alephium.flow.io.{IOResult, KeyValueStorage}
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.U64

final case class WorldState(alfOutputState: MerklePatriciaTrie[TxOutputRef, TxOutput]) {
  def get(outputRef: TxOutputRef): IOResult[TxOutput] = {
    alfOutputState.get(outputRef)
  }

  def put(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState] = {
    alfOutputState.put(outputRef, output).map(WorldState(_))
  }

  def remove(outputRef: TxOutputRef): IOResult[WorldState] = {
    alfOutputState.remove(outputRef).map(WorldState(_))
  }

  def toHashes: WorldState.Hashes = WorldState.Hashes(alfOutputState.rootHash)
}

object WorldState {
  def empty(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
    val emptyTxTrie =
      MerklePatriciaTrie.build(storage, TxOutputRef.empty, TxOutput.burn(U64.Zero))
    WorldState(emptyTxTrie)
  }

  final case class Hashes(alfStateHash: ALF.Hash) {
    def toWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
      val alfState = MerklePatriciaTrie[TxOutputRef, TxOutput](alfStateHash, storage)
      WorldState(alfState)
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct1(Hashes.apply, t => t.alfStateHash)
  }
}
