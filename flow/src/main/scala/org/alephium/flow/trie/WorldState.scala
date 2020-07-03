package org.alephium.flow.trie

import org.alephium.flow.io.{IOResult, KeyValueStorage}
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.protocol.vm.StatefulContract
import org.alephium.serde.Serde
import org.alephium.util.U64

final case class WorldState(outputState: MerklePatriciaTrie[TxOutputRef, TxOutput],
                            contractState: MerklePatriciaTrie[ALF.Hash, StatefulContract]) {
  def get(outputRef: TxOutputRef): IOResult[TxOutput] = {
    outputState.get(outputRef)
  }

  def put(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState] = {
    outputState.put(outputRef, output).map(WorldState(_, contractState))
  }

  def put(key: ALF.Hash, contract: StatefulContract): IOResult[WorldState] = {
    contractState.put(key, contract).map(WorldState(outputState, _))
  }

  def remove(outputRef: TxOutputRef): IOResult[WorldState] = {
    outputState.remove(outputRef).map(WorldState(_, contractState))
  }

  def remove(key: ALF.Hash): IOResult[WorldState] = {
    contractState.remove(key).map(WorldState(outputState, _))
  }

  def toHashes: WorldState.Hashes = WorldState.Hashes(outputState.rootHash, contractState.rootHash)
}

object WorldState {
  def empty(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
    val emptyOutputTrie =
      MerklePatriciaTrie.build(storage, TxOutputRef.empty, TxOutput.burn(U64.Zero))
    val emptyContractTrie =
      MerklePatriciaTrie.build(storage, ALF.Hash.zero, StatefulContract.failure)
    WorldState(emptyOutputTrie, emptyContractTrie)
  }

  final case class Hashes(outputStateHash: ALF.Hash, contractStateHash: ALF.Hash) {
    def toWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
      val outputState   = MerklePatriciaTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = MerklePatriciaTrie[ALF.Hash, StatefulContract](contractStateHash, storage)
      WorldState(outputState, contractState)
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
