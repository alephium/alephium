// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

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

  def getContractAsset(key: Hash): IOResult[ContractOutput] = {
    for {
      state     <- getContractState(key)
      outputRaw <- getOutput(state.contractOutputRef)
      output <- outputRaw match {
        case _: AssetOutput =>
          val error = s"ContractOutput expected, but got AssetOutput for contract $key"
          Left(IOError.Other(new RuntimeException(error)))
        case o: ContractOutput =>
          Right(o)
      }
    } yield output
  }

  def useContractAsset(
      contractId: ContractId): IOResult[(ContractOutputRef, ContractOutput, WorldState)] = {
    for {
      state     <- getContractState(contractId)
      outputRaw <- getOutput(state.contractOutputRef)
      output <- outputRaw match {
        case _: AssetOutput =>
          val error = s"ContractOutput expected, but got AssetOutput for contract $contractId"
          Left(IOError.Other(new RuntimeException(error)))
        case o: ContractOutput =>
          Right(o)
      }
      newWorldState <- removeAsset(state.contractOutputRef)
    } yield (state.contractOutputRef, output, newWorldState)
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
                     output: ContractOutput): IOResult[WorldState]

  def updateContract(key: Hash, fields: AVector[Val]): IOResult[WorldState] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, oldState.copy(fields = fields))
    } yield newState
  }

  def updateContract(key: Hash,
                     outputRef: ContractOutputRef,
                     output: ContractOutput): IOResult[WorldState]

  protected[vm] def updateContract(key: Hash, state: ContractState): IOResult[WorldState]

  def removeAsset(outputRef: TxOutputRef): IOResult[WorldState]

  def removeContract(contractKey: Hash): IOResult[WorldState]

  def persist: IOResult[WorldState.Persisted]

  def getPreOutputsForVM(tx: TransactionAbstract): IOResult[AVector[TxOutput]] = {
    tx.unsigned.inputs.mapE { input =>
      getOutput(input.outputRef)
    }
  }

  def getPreOutputs(tx: Transaction): IOResult[AVector[TxOutput]] = {
    for {
      fixedInputs <- tx.unsigned.inputs.mapE { input =>
        getOutput(input.outputRef)
      }
      contractInputs <- tx.contractInputs.mapE { outputRef =>
        getOutput(outputRef)
      }
    } yield (fixedInputs ++ contractInputs)
  }
}

object WorldState {
  final case class Persisted(
      outputState: SparseMerkleTrie[TxOutputRef, TxOutput],
      contractState: SparseMerkleTrie[Hash, ContractState]
  ) extends WorldState {
    override def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getAllOutputs(outputRefPrefix: ByteString): IOResult[AVector[(TxOutputRef, TxOutput)]] = {
      outputState.getAll(outputRefPrefix)
    }

    def getAssetOutputs(
        outputRefPrefix: ByteString): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
      outputState
        .getAll(outputRefPrefix)
        .map(
          _.filter(p => p._1.isAssetType && p._2.isAsset).asUnsafe[(AssetOutputRef, AssetOutput)])
    }

    def getContractOutputs(
        outputRefPrefix: ByteString): IOResult[AVector[(ContractOutputRef, ContractOutput)]] = {
      getAllOutputs(outputRefPrefix).map(
        _.filter(p => p._1.isContractType && !p._2.isAsset)
          .asUnsafe[(ContractOutputRef, ContractOutput)])
    }

    override def getContractState(key: Hash): IOResult[ContractState] = {
      contractState.get(key)
    }

    def getContractStates(): IOResult[AVector[(ContractId, ContractState)]] = {
      contractState.getAll(ByteString.empty)
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
                                output: ContractOutput): IOResult[WorldState] = {
      val state = ContractState(code, fields, outputRef)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(outputRef.key, state)
      } yield Persisted(newOutputState, newContractState)
    }

    override def updateContract(key: Hash, state: ContractState): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _))
    }

    def updateContract(key: Hash,
                       outputRef: ContractOutputRef,
                       output: ContractOutput): IOResult[WorldState] = {
      for {
        state            <- getContractState(key)
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(key, state.copy(contractOutputRef = outputRef))
      } yield Persisted(newOutputState, newContractState)
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
      val outputStateCache    = CachedSMT.from(outputState)
      val contractOutputCache = CachedSMT.from(contractState)
      Cached(outputStateCache, contractOutputCache)
    }

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash)
  }

  final case class Cached(
      outputState: CachedSMT[TxOutputRef, TxOutput],
      contractState: CachedSMT[Hash, ContractState]
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
                                output: ContractOutput): IOResult[WorldState] = {
      val state = ContractState(code, fields, outputRef)
      for {
        _ <- outputState.put(outputRef, output)
        _ <- contractState.put(outputRef.key, state)
      } yield this
    }

    override def updateContract(key: Hash, state: ContractState): IOResult[Cached] = {
      contractState.put(key, state).map(_ => this)
    }

    def updateContract(key: Hash,
                       outputRef: ContractOutputRef,
                       output: ContractOutput): IOResult[WorldState] = {
      for {
        state <- getContractState(key)
        _     <- outputState.put(outputRef, output)
        _     <- contractState.put(key, state.copy(contractOutputRef = outputRef))
      } yield this
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

  def emptyPersisted(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Persisted = {
    val genesisRef  = ContractOutputRef.forMPT
    val emptyOutput = TxOutput.forMPT
    val emptyOutputTrie =
      SparseMerkleTrie.build[TxOutputRef, TxOutput](storage, genesisRef, emptyOutput)
    val emptyState        = ContractState(StatefulContract.forMPT, AVector.empty, genesisRef)
    val emptyContractTrie = SparseMerkleTrie.build(storage, Hash.zero, emptyState)
    Persisted(emptyOutputTrie, emptyContractTrie)
  }

  def emptyCached(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Cached = {
    emptyPersisted(storage).cached
  }

  final case class Hashes(outputStateHash: Hash, contractStateHash: Hash) {
    def toPersistedWorldState(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Persisted = {
      val outputState   = SparseMerkleTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = SparseMerkleTrie[Hash, ContractState](contractStateHash, storage)
      Persisted(outputState, contractState)
    }

    def toCachedWorldState(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Cached = {
      val initialState = toPersistedWorldState(storage)
      initialState.cached
    }
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
