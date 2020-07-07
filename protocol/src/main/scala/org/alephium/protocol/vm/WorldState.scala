package org.alephium.protocol.vm

import org.alephium.io.{IOResult, KeyValueStorage, MerklePatriciaTrie}
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.{AVector, U64}

final case class WorldState(outputState: MerklePatriciaTrie[TxOutputRef, TxOutput],
                            contractState: MerklePatriciaTrie[ALF.Hash, StatelessScript]) {
  def get(outputRef: TxOutputRef): IOResult[TxOutput] = {
    outputState.get(outputRef)
  }

  def put(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState] = {
    outputState.put(outputRef, output).map(WorldState(_, contractState))
  }

  def put(key: ALF.Hash, contract: StatelessScript): IOResult[WorldState] = {
    contractState.put(key, contract).map(WorldState(outputState, _))
  }

  def exist(contractKey: ALF.Hash): IOResult[Boolean] = {
    contractState.getOpt(contractKey).map(_.nonEmpty)
  }

  def remove(outputRef: TxOutputRef): IOResult[WorldState] = {
    outputState.remove(outputRef).map(WorldState(_, contractState))
  }

  def remove(key: ALF.Hash): IOResult[WorldState] = {
    contractState.remove(key).map(WorldState(outputState, _))
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def update(key: ALF.Hash, state: AVector[Val]): IOResult[WorldState] = {
    val outputRef = TxOutputRef.contract(key)
    for {
      output <- get(outputRef)
      newOutput = output.asInstanceOf[ContractOutput].copy(state = state)
      worldState <- put(outputRef, newOutput)
    } yield worldState
  }

  def toHashes: WorldState.Hashes =
    WorldState.Hashes(outputState.rootHash, contractState.rootHash)
}

object WorldState {
  def empty(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
    val emptyOutputTrie =
      MerklePatriciaTrie.build(storage, TxOutputRef.empty, TxOutput.burn(U64.Zero))
    val emptyContractTrie =
      MerklePatriciaTrie.build(storage, ALF.Hash.zero, StatelessScript.failure)
    WorldState(emptyOutputTrie, emptyContractTrie)
  }

  val mock: WorldState = {
    val outputState: MerklePatriciaTrie[TxOutputRef, TxOutput] =
      MerklePatriciaTrie(ALF.Hash.zero, KeyValueStorage.mock[ALF.Hash, MerklePatriciaTrie.Node])
    val contractState: MerklePatriciaTrie[ALF.Hash, StatelessScript] =
      MerklePatriciaTrie(ALF.Hash.zero, KeyValueStorage.mock[ALF.Hash, MerklePatriciaTrie.Node])
    WorldState(outputState, contractState)
  }

  final case class Hashes(outputStateHash: ALF.Hash, contractStateHash: ALF.Hash) {
    def toWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
      val outputState   = MerklePatriciaTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = MerklePatriciaTrie[ALF.Hash, StatelessScript](contractStateHash, storage)
      WorldState(outputState, contractState)
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
