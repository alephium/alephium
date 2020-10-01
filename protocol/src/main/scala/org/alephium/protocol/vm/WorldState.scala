package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.io._
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.serde.Serde
import org.alephium.util.AVector

sealed abstract class WorldState {
  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput]

  def getContractState(key: Hash): IOResult[ContractState]

  def getContractAsset(key: Hash): IOResult[TxOutput] = {
    for {
      state  <- getContractState(key)
      output <- getOutput(state.contractOutputRef)
    } yield output
  }

  def getContractObj(key: Hash): IOResult[StatefulContractObject] = {
    for {
      state <- getContractState(key)
    } yield state.code.toObject(key, state)
  }

  def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState]

  def createContract(code: StatefulContract,
                     fields: AVector[Val],
                     outputRef: ContractOutputRef,
                     output: TxOutput): IOResult[WorldState]

  def updateContract(key: Hash, fields: AVector[Val]): IOResult[WorldState] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, oldState.copy(fields = fields))
    } yield newState
  }

  protected[vm] def updateContract(key: Hash, state: ContractState): IOResult[WorldState]

  def removeAsset(outputRef: TxOutputRef): IOResult[WorldState]

  def removeContract(contractKey: Hash): IOResult[WorldState]

  def persist: IOResult[WorldState.Persisted]

  def getPreOutputs(tx: TransactionAbstract): IOResult[AVector[TxOutput]] = {
    tx.unsigned.inputs.mapE { input =>
      getOutput(input.outputRef)
    }
  }
}

object WorldState {
  final case class Persisted(
      outputState: MerklePatriciaTrie[TxOutputRef, TxOutput],
      contractState: MerklePatriciaTrie[Hash, ContractState]
  ) extends WorldState {
    override def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getOutputs(
        outputRefPrefix: ByteString): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
      outputState
        .getAll(outputRefPrefix)
        .map(
          _.filter(p => p._1.isAssetType && p._2.isAsset).asUnsafe[(AssetOutputRef, AssetOutput)])
    }

    override def getContractState(key: Hash): IOResult[ContractState] = {
      contractState.get(key)
    }

    override def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[WorldState] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    private[WorldState] def putOutput(outputRef: TxOutputRef,
                                      output: TxOutput): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    override def createContract(code: StatefulContract,
                                fields: AVector[Val],
                                outputRef: ContractOutputRef,
                                output: TxOutput): IOResult[WorldState] = {
      val state = ContractState(code, fields, outputRef)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(outputRef.key, state)
      } yield Persisted(newOutputState, newContractState)
    }

    override def updateContract(key: Hash, state: ContractState): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _))
    }

    override def removeAsset(outputRef: TxOutputRef): IOResult[Persisted] = {
      outputState.remove(outputRef).map(Persisted(_, contractState))
    }

    override def removeContract(contractKey: Hash): IOResult[WorldState] = {
      for {
        state            <- getContractState(contractKey)
        newOutputState   <- outputState.remove(state.contractOutputRef)
        newContractState <- contractState.remove(contractKey)
      } yield Persisted(newOutputState, newContractState)
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

    override def getContractState(key: Hash): IOResult[ContractState] = {
      contractState.get(key)
    }

    override def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Cached] = {
      outputState.put(outputRef, output).map(_ => this)
    }

    override def createContract(code: StatefulContract,
                                fields: AVector[Val],
                                outputRef: ContractOutputRef,
                                output: TxOutput): IOResult[WorldState] = {
      val state = ContractState(code, fields, outputRef)
      for {
        _ <- outputState.put(outputRef, output)
        _ <- contractState.put(outputRef.key, state)
      } yield this
    }

    override def updateContract(key: Hash, state: ContractState): IOResult[Cached] = {
      contractState.put(key, state).map(_ => this)
    }

    override def removeAsset(outputRef: TxOutputRef): IOResult[Cached] = {
      outputState.remove(outputRef).map(_ => this)
    }

    override def removeContract(contractKey: Hash): IOResult[WorldState] = {
      for {
        state <- getContractState(contractKey)
        _     <- outputState.remove(state.contractOutputRef)
        _     <- contractState.remove(contractKey)
      } yield this
    }

    override def persist: IOResult[Persisted] = {
      for {
        outputStateNew   <- outputState.persist()
        contractStateNew <- contractState.persist()
      } yield Persisted(outputStateNew, contractStateNew)
    }
  }

  def emptyPersisted(storage: KeyValueStorage[Hash, MerklePatriciaTrie.Node]): Persisted = {
    val genesisRef  = ContractOutputRef.forMPT
    val emptyOutput = TxOutput.forMPT
    val emptyOutputTrie =
      MerklePatriciaTrie.build[TxOutputRef, TxOutput](storage, genesisRef, emptyOutput)
    val emptyState        = ContractState(StatefulContract.forMPT, AVector.empty, genesisRef)
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
