package org.alephium.flow.trie

import org.alephium.flow.io.{IOResult, KeyValueStorage}
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.U64

final case class WorldState(alfOutputState: MerklePatriciaTrie[AlfOutputRef, AlfOutput],
                            tokenOutputState: MerklePatriciaTrie[TokenOutputRef, TokenOutput]) {
  def get(outputRef: TxOutputRef): IOResult[TxOutput] = {
    outputRef match {
      case alfOutputRef: AlfOutputRef =>
        alfOutputState.get(alfOutputRef)
      case tokenOutputRef: TokenOutputRef =>
        tokenOutputState.get(tokenOutputRef)
    }
  }

  def put(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState] = {
    (outputRef, output) match {
      case (alfOutputRef: AlfOutputRef, alfOutput: AlfOutput) =>
        alfOutputState.put(alfOutputRef, alfOutput).map(WorldState(_, tokenOutputState))
      case (tokenOutputRef: TokenOutputRef, tokenOutput: TokenOutput) =>
        tokenOutputState.put(tokenOutputRef, tokenOutput).map(WorldState(alfOutputState, _))
      case _ => throw new IllegalArgumentException("Different types of outputRef and output")
    }
  }

  def remove(outputRef: TxOutputRef): IOResult[WorldState] = {
    outputRef match {
      case alfOutputRef: AlfOutputRef =>
        alfOutputState.remove(alfOutputRef).map(WorldState(_, tokenOutputState))
      case tokenOutputRef: TokenOutputRef =>
        tokenOutputState.remove(tokenOutputRef).map(WorldState(alfOutputState, _))
    }
  }

  def toHashes: WorldState.Hashes =
    WorldState.Hashes(alfOutputState.rootHash, tokenOutputState.rootHash)
}

object WorldState {
  def empty(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
    val emptyAlfTrie =
      MerklePatriciaTrie.build(storage, AlfOutputRef.empty, AlfOutput.burn(U64.Zero))
    val emptyTokenTrie =
      MerklePatriciaTrie.build(storage, TokenOutputRef.empty, TokenOutput.burn(U64.Zero))
    WorldState(emptyAlfTrie, emptyTokenTrie)
  }

  final case class Hashes(alfStateHash: ALF.Hash, tokenStateHash: ALF.Hash) {
    def toWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
      val alfState   = MerklePatriciaTrie[AlfOutputRef, AlfOutput](alfStateHash, storage)
      val tokenState = MerklePatriciaTrie[TokenOutputRef, TokenOutput](tokenStateHash, storage)
      WorldState(alfState, tokenState)
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => (t.alfStateHash, t.tokenStateHash))
  }
}
