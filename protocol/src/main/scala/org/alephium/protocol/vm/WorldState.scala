package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.io.{IOError, IOResult, KeyValueStorage, MerklePatriciaTrie}
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.{AVector, EitherF, U64}

sealed trait WorldState {
  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput]

  def getOutputs(outputRefPrefix: ByteString): IOResult[AVector[(TxOutputRef, TxOutput)]]

  def getContractState(key: ALF.Hash): IOResult[AVector[Val]]

  def putOutput(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState]

  def putContractState(key: ALF.Hash, state: AVector[Val]): IOResult[WorldState]

  def existContract(contractKey: ALF.Hash): IOResult[Boolean]

  def remove(outputRef: TxOutputRef): IOResult[WorldState]

  def persist: IOResult[WorldState.Persisted]
}

object WorldState {
  final case class Persisted(outputState: MerklePatriciaTrie[TxOutputRef, TxOutput],
                             contractState: MerklePatriciaTrie[ALF.Hash, AVector[Val]])
      extends WorldState {
    def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getOutputs(outputRefPrefix: ByteString): IOResult[AVector[(TxOutputRef, TxOutput)]] = {
      outputState.getAll(outputRefPrefix)
    }

    def getContractState(key: ALF.Hash): IOResult[AVector[Val]] = {
      contractState.get(key)
    }

    def putOutput(outputRef: TxOutputRef, output: TxOutput): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    def putContractState(key: ALF.Hash, state: AVector[Val]): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _))
    }

    def existContract(contractKey: ALF.Hash): IOResult[Boolean] = {
      contractState.getOpt(contractKey).map(_.nonEmpty)
    }

    def remove(outputRef: TxOutputRef): IOResult[Persisted] = {
      if (outputRef.isContractRef) {
        for {
          newOutputState   <- outputState.remove(outputRef)
          newContractState <- contractState.remove(outputRef.key)
        } yield Persisted(newOutputState, newContractState)
      } else outputState.remove(outputRef).map(Persisted(_, contractState))
    }

    def persist: IOResult[WorldState.Persisted] = Right(this)

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash)
  }

  // TODO: add cache for initialState
  final case class Cached(initialState: Persisted,
                          outputStateDeletes: Set[TxOutputRef],
                          outputStateAdditions: Map[TxOutputRef, TxOutput],
                          contractStateChanges: Map[ALF.Hash, AVector[Val]])
      extends WorldState {
    override def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      if (outputStateAdditions.contains(outputRef)) Right(outputStateAdditions(outputRef))
      else if (outputStateDeletes.contains(outputRef)) Left(IOError.KeyNotFound(outputRef))
      else initialState.getOutput(outputRef)
    }

    override def getContractState(key: ALF.Hash): IOResult[AVector[Val]] = {
      contractStateChanges.get(key) match {
        case Some(state) => Right(state)
        case None        => initialState.getContractState(key)
      }
    }

    override def getOutputs(
        outputRefPrefix: ByteString): IOResult[AVector[(TxOutputRef, TxOutput)]] = {
      initialState.getOutputs(outputRefPrefix)
    }

    override def putOutput(outputRef: TxOutputRef, output: TxOutput): IOResult[Cached] = {
      Right(this.copy(outputStateAdditions = outputStateAdditions + (outputRef -> output)))
    }

    override def putContractState(key: ALF.Hash, state: AVector[Val]): IOResult[Cached] = {
      Right(this.copy(contractStateChanges = contractStateChanges + (key -> state)))
    }

    override def existContract(contractKey: ALF.Hash): IOResult[Boolean] = {
      if (contractStateChanges.contains(contractKey)) Right(true)
      else initialState.existContract(contractKey)
    }

    override def remove(outputRef: TxOutputRef): IOResult[Cached] = {
      if (outputStateAdditions.contains(outputRef)) {
        Right(this.copy(outputStateAdditions = outputStateAdditions - outputRef))
      } else {
        Right(this.copy(outputStateDeletes = outputStateDeletes + outputRef))
      }
    }

    def persist: IOResult[Persisted] = {
      for {
        state0 <- EitherF.foldTry(contractStateChanges, initialState) {
          case (worldState, (key, contractState)) => worldState.putContractState(key, contractState)
        }
        state1 <- EitherF.foldTry(outputStateDeletes, state0) {
          case (worldState, outputRef) => worldState.remove(outputRef)
        }
        state2 <- EitherF.foldTry(outputStateAdditions, state1) {
          case (worldState, (outputRef, output)) => worldState.putOutput(outputRef, output)
        }
      } yield state2
    }
  }

  def empty(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): WorldState = {
    val emptyOutputTrie =
      MerklePatriciaTrie.build(storage, TxOutputRef.empty, TxOutput.burn(U64.Zero))
    val emptyContractTrie =
      MerklePatriciaTrie.build(storage, ALF.Hash.zero, AVector.empty[Val])
    Persisted(emptyOutputTrie, emptyContractTrie)
  }

  val mock: WorldState = {
    val outputState: MerklePatriciaTrie[TxOutputRef, TxOutput] =
      MerklePatriciaTrie(ALF.Hash.zero, KeyValueStorage.mock[ALF.Hash, MerklePatriciaTrie.Node])
    val contractState: MerklePatriciaTrie[ALF.Hash, AVector[Val]] =
      MerklePatriciaTrie(ALF.Hash.zero, KeyValueStorage.mock[ALF.Hash, MerklePatriciaTrie.Node])
    Cached(Persisted(outputState, contractState), Set.empty, Map.empty, Map.empty)
  }

  final case class Hashes(outputStateHash: ALF.Hash, contractStateHash: ALF.Hash) {
    def toWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): Persisted = {
      val outputState   = MerklePatriciaTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = MerklePatriciaTrie[ALF.Hash, AVector[Val]](contractStateHash, storage)
      Persisted(outputState, contractState)
    }

    def toCachedWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): Cached = {
      val initialState = toWorldState(storage)
      Cached(initialState, Set.empty, Map.empty, Map.empty)
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
