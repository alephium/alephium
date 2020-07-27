package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.io.{IOError, IOResult, KeyValueStorage, MerklePatriciaTrie}
import org.alephium.protocol.ALF
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.{AVector, EitherF, U64}

sealed abstract class WorldState {
  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput]

  protected[vm] def getContractState(key: ALF.Hash): IOResult[AVector[Val]]

  def getContractObj(key: ALF.Hash): IOResult[StatefulContractObject] = {
    for {
      contractOutput <- getOutput(TxOutputRef.contract(key)).map(_.asInstanceOf[ContractOutput])
      state          <- getContractState(key)
    } yield contractOutput.code.toObject(key, state)
  }

  def addAsset(outputRef: AssetOutputRef, output: AssetOutput): IOResult[WorldState]

  def addContract(outputRef: ContractOutputRef,
                  output: ContractOutput,
                  state: AVector[Val]): IOResult[WorldState]

  def updateContract(key: ALF.Hash, state: AVector[Val]): IOResult[WorldState]

  def remove(outputRef: TxOutputRef): IOResult[WorldState]

  def persist: IOResult[WorldState.Persisted]
}

object WorldState {
  final case class Persisted(outputState: MerklePatriciaTrie[TxOutputRef, TxOutput],
                             contractState: MerklePatriciaTrie[ALF.Hash, AVector[Val]])
      extends WorldState {
    override def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getOutputs(outputRefPrefix: ByteString): IOResult[AVector[(TxOutputRef, TxOutput)]] = {
      outputState.getAll(outputRefPrefix)
    }

    override def getContractState(key: ALF.Hash): IOResult[AVector[Val]] = {
      contractState.get(key)
    }

    override def addAsset(outputRef: AssetOutputRef, output: AssetOutput): IOResult[WorldState] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    private[WorldState] def putOutput(outputRef: TxOutputRef,
                                      output: TxOutput): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    override def addContract(outputRef: ContractOutputRef,
                             output: ContractOutput,
                             state: AVector[Val]): IOResult[WorldState] = {
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(outputRef.key, state)
      } yield Persisted(newOutputState, newContractState)
    }

    override def updateContract(key: ALF.Hash, state: AVector[Val]): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _))
    }

    override def remove(outputRef: TxOutputRef): IOResult[Persisted] = {
      outputRef match {
        case _: AssetOutputRef =>
          outputState.remove(outputRef).map(Persisted(_, contractState))
        case ContractOutputRef(key) =>
          for {
            newOutputState   <- outputState.remove(outputRef)
            newContractState <- contractState.remove(key)
          } yield Persisted(newOutputState, newContractState)
      }
    }

    override def persist: IOResult[WorldState.Persisted] = Right(this)

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash)
  }

  /**
    * TODO: add cache for initialState; and make this mutable for performance
    *
    * @param initialState the initial persisted WorldState
    * @param outputStateDeletes the outputs to be deleted from the persisted WorldState
    *                           all the outputs should exist in the persisted WorldState
    * @param outputStateAdditions the outputs to be added into the persisted WorldState
    * @param contractStateChanges the outputs to be updated for the persisted WorldState
    *                             there might be new contracts
    */
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

    override protected[vm] def getContractState(key: ALF.Hash): IOResult[AVector[Val]] = {
      contractStateChanges.get(key) match {
        case Some(state) => Right(state)
        case None        => initialState.getContractState(key)
      }
    }

    override def addAsset(outputRef: AssetOutputRef, output: AssetOutput): IOResult[Cached] = {
      Right(this.copy(outputStateAdditions = outputStateAdditions + (outputRef -> output)))
    }

    override def addContract(outputRef: ContractOutputRef,
                             output: ContractOutput,
                             state: AVector[Val]): IOResult[WorldState] = {
      Right(
        this.copy(outputStateAdditions = outputStateAdditions + (outputRef     -> output),
                  contractStateChanges = contractStateChanges + (outputRef.key -> state)))
    }

    override def updateContract(key: ALF.Hash, state: AVector[Val]): IOResult[Cached] = {
      Right(this.copy(contractStateChanges = contractStateChanges + (key -> state)))
    }

    // Note: we don't check if the output exist. This is fine as we only use it to remove validated tx input
    override def remove(outputRef: TxOutputRef): IOResult[Cached] = {
      if (outputStateAdditions.contains(outputRef)) {
        Right(this.copy(outputStateAdditions = outputStateAdditions - outputRef))
      } else {
        Right(this.copy(outputStateDeletes = outputStateDeletes + outputRef))
      }
    }

    override def persist: IOResult[Persisted] = {
      for {
        state0 <- EitherF.foldTry(contractStateChanges, initialState) {
          case (worldState, (key, contractState)) =>
            worldState.updateContract(key, contractState)
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

  def emptyPersisted(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): Persisted = {
    val emptyOutputTrie =
      MerklePatriciaTrie.build(storage, TxOutputRef.emptyTreeNode, TxOutput.burn(U64.Zero))
    val emptyContractTrie =
      MerklePatriciaTrie.build(storage, ALF.Hash.zero, AVector.empty[Val])
    Persisted(emptyOutputTrie, emptyContractTrie)
  }

  def emptyCached(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): Cached = {
    val persisted = emptyPersisted(storage)
    Cached(persisted, Set.empty, Map.empty, Map.empty)
  }

  final case class Hashes(outputStateHash: ALF.Hash, contractStateHash: ALF.Hash) {
    def toPersistedWorldState(
        storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): Persisted = {
      val outputState   = MerklePatriciaTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = MerklePatriciaTrie[ALF.Hash, AVector[Val]](contractStateHash, storage)
      Persisted(outputState, contractState)
    }

    def toCachedWorldState(storage: KeyValueStorage[ALF.Hash, MerklePatriciaTrie.Node]): Cached = {
      val initialState = toPersistedWorldState(storage)
      Cached(initialState, Set.empty, Map.empty, Map.empty)
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
