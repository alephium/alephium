package org.alephium.protocol.vm

import java.util.InputMismatchException

import akka.util.ByteString

import org.alephium.io._
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.AVector

sealed abstract class WorldState {
  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput]

  protected[vm] def getContractState(key: Hash): IOResult[ContractState]

  def getContractObj(key: Hash): IOResult[StatefulContractObject] = {
    for {
      state <- getContractState(key)
      output <- {
        assume(state.hint.isContractType)
        val ref = ContractOutputRef.unsafe(state.hint, key)
        getOutput(ref)
      }
      contractOutput <- output match {
        case _: AssetOutput =>
          val error = new InputMismatchException("Expect ContractOutput, but got AssetOutput")
          Left(IOError.Other(error))
        case output: ContractOutput => Right(output)
      }
    } yield contractOutput.code.toObject(key, state)
  }

  def addAsset(outputRef: AssetOutputRef, output: AssetOutput): IOResult[WorldState]

  def addContract(outputRef: ContractOutputRef,
                  output: ContractOutput,
                  fields: AVector[Val]): IOResult[WorldState]

  def updateContract(key: Hash, fields: AVector[Val]): IOResult[WorldState] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, ContractState(oldState.hint, fields))
    } yield newState
  }

  def updateContract(key: Hash, state: ContractState): IOResult[WorldState]

  def remove(outputRef: TxOutputRef): IOResult[WorldState]

  def persist: IOResult[WorldState.Persisted]
}

object WorldState {
  final case class Persisted(
      outputState: MerklePatriciaTrie[TxOutputRef, TxOutput],
      contractState: MerklePatriciaTrie[Hash, ContractState]
  ) extends WorldState {
    override def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getOutputs(outputRefPrefix: ByteString): IOResult[AVector[(TxOutputRef, TxOutput)]] = {
      outputState.getAll(outputRefPrefix)
    }

    override def getContractState(key: Hash): IOResult[ContractState] = {
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
                             fields: AVector[Val]): IOResult[WorldState] = {
      val state = ContractState(outputRef.hint, fields)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(outputRef.key, state)
      } yield Persisted(newOutputState, newContractState)
    }

    override def updateContract(key: Hash, state: ContractState): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _))
    }

    override def remove(outputRef: TxOutputRef): IOResult[Persisted] = {
      if (outputRef.isAssetType) {
        outputState.remove(outputRef).map(Persisted(_, contractState))
      } else {
        for {
          newOutputState   <- outputState.remove(outputRef)
          newContractState <- contractState.remove(outputRef.key)
        } yield Persisted(newOutputState, newContractState)
      }
    }

    override def persist: IOResult[WorldState.Persisted] = Right(this)

    def cached: WorldState.Cached = {
      val outputStateCache    = CachedTrie.from(outputState)
      val contractOutputCache = CachedTrie.from(contractState)
      Cached(outputStateCache, contractOutputCache)
    }

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash)
  }

  final case class Cached(
      outputState: CachedTrie[TxOutputRef, TxOutput],
      contractState: CachedTrie[Hash, ContractState]
  ) extends WorldState {
    override def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    override protected[vm] def getContractState(key: Hash): IOResult[ContractState] = {
      contractState.get(key)
    }

    override def addAsset(outputRef: AssetOutputRef, output: AssetOutput): IOResult[Cached] = {
      outputState.put(outputRef, output).map(_ => this)
    }

    override def addContract(outputRef: ContractOutputRef,
                             output: ContractOutput,
                             fields: AVector[Val]): IOResult[WorldState] = {
      val state = ContractState(outputRef.hint, fields)
      for {
        _ <- outputState.put(outputRef, output)
        _ <- contractState.put(outputRef.key, state)
      } yield this
    }

    override def updateContract(key: Hash, state: ContractState): IOResult[Cached] = {
      contractState.put(key, state).map(_ => this)
    }

    override def remove(outputRef: TxOutputRef): IOResult[Cached] = {
      if (outputRef.isAssetType) {
        outputState.remove(outputRef).map(_ => this)
      } else {
        for {
          _ <- outputState.remove(outputRef)
          _ <- contractState.remove(outputRef.key)
        } yield this
      }
    }

    override def persist: IOResult[Persisted] = {
      for {
        outputStateNew   <- outputState.persist()
        contractStateNew <- contractState.persist()
      } yield Persisted(outputStateNew, contractStateNew)
    }
  }

  def emptyPersisted(storage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]): Persisted = {
    val genesisRef        = TxOutputRef.emptyTreeNode
    val emptyOutput       = TxOutput.forMPT
    val emptyOutputTrie   = MerklePatriciaTrie.build(storage, genesisRef, emptyOutput)
    val emptyState        = ContractState(genesisRef.hint, AVector.empty)
    val emptyContractTrie = MerklePatriciaTrie.build(storage, Hash.zero, emptyState)
    Persisted(emptyOutputTrie, emptyContractTrie)
  }

  def emptyCached(storage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]): Cached = {
    emptyPersisted(storage).cached
  }

  final case class Hashes(outputStateHash: Hash, contractStateHash: Hash) {
    def toPersistedWorldState(
        storage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]): Persisted = {
      val outputState   = MerklePatriciaTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = MerklePatriciaTrie[Hash, ContractState](contractStateHash, storage)
      Persisted(outputState, contractState)
    }

    def toCachedWorldState(storage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]): Cached = {
      val initialState = toPersistedWorldState(storage)
      initialState.cached
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
