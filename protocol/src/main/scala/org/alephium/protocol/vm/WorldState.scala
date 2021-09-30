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
import org.alephium.serde.{Serde, SerdeError}
import org.alephium.util.AVector

trait WorldState[T] {
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getAsset(outputRef: AssetOutputRef): IOResult[AssetOutput] = {
    // we use asInstanceOf for optimization
    getOutput(outputRef) match {
      case Right(_: ContractOutput) => Left(WorldState.expectedAssetError)
      case result                   => result.asInstanceOf[IOResult[AssetOutput]]
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getAssetOpt(outputRef: AssetOutputRef): IOResult[Option[AssetOutput]] = {
    // we use asInstanceOf for optimization
    getOutputOpt(outputRef) match {
      case Right(Some(_: ContractOutput)) => Left(WorldState.expectedAssetError)
      case result                         => result.asInstanceOf[IOResult[Option[AssetOutput]]]
    }
  }

  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput]

  def getOutputOpt(outputRef: TxOutputRef): IOResult[Option[TxOutput]]

  def existOutput(outputRef: TxOutputRef): IOResult[Boolean]

  def getContractState(key: Hash): IOResult[ContractState]

  def getContractAsset(key: Hash): IOResult[ContractOutput]

  def getContractObj(key: Hash): IOResult[StatefulContractObject]

  def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[T]

  def createContractUnsafe(
      code: StatefulContract.HalfDecoded,
      initialStateHash: Hash,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[T]

  def updateContractUnsafe(key: Hash, fields: AVector[Val]): IOResult[T]

  def updateContract(key: Hash, outputRef: ContractOutputRef, output: ContractOutput): IOResult[T]

  protected[vm] def updateContract(key: Hash, state: ContractState): IOResult[T]

  def removeAsset(outputRef: TxOutputRef): IOResult[T]

  def removeContract(contractKey: Hash): IOResult[T]

  def persist(): IOResult[WorldState.Persisted]

  def getPreOutputsForAssetInputs(
      tx: TransactionAbstract
  ): IOResult[Option[AVector[AssetOutput]]] = {
    val inputs = tx.unsigned.inputs
    inputs.foldE[IOError, Option[AVector[AssetOutput]]](Some(AVector.ofSize(inputs.length))) {
      case (Some(outputs), input) =>
        getAssetOpt(input.outputRef).map {
          case Some(output) => Some(outputs :+ output)
          case None         => None
        }
      case (None, _) => Right(None)
    }
  }

  def getPreOutputs(tx: Transaction): IOResult[AVector[TxOutput]] = {
    for {
      fixedInputs    <- tx.unsigned.inputs.mapE { input => getOutput(input.outputRef) }
      contractInputs <- tx.contractInputs.mapE { outputRef => getOutput(outputRef) }
    } yield (fixedInputs ++ contractInputs)
  }

  def containsAllInputs(tx: TransactionTemplate): IOResult[Boolean] = {
    tx.unsigned.inputs.forallE(input => existOutput(input.outputRef))
  }

  def getAssetOutputs(
      outputRefPrefix: ByteString,
      maxOutputs: Int,
      predicate: (TxOutputRef, TxOutput) => Boolean
  ): IOResult[AVector[(AssetOutputRef, AssetOutput)]]
}

sealed abstract class MutableWorldState extends WorldState[Unit] {
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

  def useContractAsset(contractId: ContractId): IOResult[(ContractOutputRef, ContractOutput)] = {
    for {
      state     <- getContractState(contractId)
      outputRaw <- getOutput(state.contractOutputRef)
      output <- outputRaw match {
        case _: AssetOutput =>
          val error =
            s"ContractOutput expected, but got AssetOutput for contract $contractId"
          Left(IOError.Other(new RuntimeException(error)))
        case o: ContractOutput =>
          Right(o)
      }
      _ <- removeAsset(state.contractOutputRef)
    } yield (state.contractOutputRef, output)
  }

  def getContractObj(key: Hash): IOResult[StatefulContractObject] = {
    for {
      state <- getContractState(key)
    } yield state.toObject(key)
  }

  def createContractUnsafe(
      code: StatefulContract.HalfDecoded,
      initialStateHash: Hash,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[Unit]

  def updateContractUnsafe(key: Hash, fields: AVector[Val]): IOResult[Unit] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, oldState.updateFieldsUnsafe(fields))
    } yield newState
  }

  def updateContract(
      key: Hash,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[Unit]
}

sealed abstract class ImmutableWorldState extends WorldState[ImmutableWorldState] {
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
      contractId: ContractId
  ): IOResult[(ContractOutputRef, ContractOutput, ImmutableWorldState)] = {
    for {
      state     <- getContractState(contractId)
      outputRaw <- getOutput(state.contractOutputRef)
      output <- outputRaw match {
        case _: AssetOutput =>
          val error =
            s"ContractOutput expected, but got AssetOutput for contract $contractId"
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
    } yield state.toObject(key)
  }

  def createContractUnsafe(
      code: StatefulContract.HalfDecoded,
      initialStateHash: Hash,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[ImmutableWorldState]

  def updateContractUnsafe(key: Hash, fields: AVector[Val]): IOResult[ImmutableWorldState] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, oldState.updateFieldsUnsafe(fields))
    } yield newState
  }
}

object WorldState {
  val expectedAssetError: IOError = IOError.Serde(SerdeError.validation("Expect AssetOutput"))

  final case class Persisted(
      outputState: SparseMerkleTrie[TxOutputRef, TxOutput],
      contractState: SparseMerkleTrie[Hash, ContractState]
  ) extends ImmutableWorldState {
    def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getOutputOpt(outputRef: TxOutputRef): IOResult[Option[TxOutput]] = {
      outputState.getOpt(outputRef)
    }

    def existOutput(outputRef: TxOutputRef): IOResult[Boolean] = {
      outputState.exist(outputRef)
    }

    def getAssetOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int,
        predicate: (TxOutputRef, TxOutput) => Boolean
    ): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
      outputState
        .getAll(
          outputRefPrefix,
          maxOutputs,
          (outputRef, output) =>
            predicate(outputRef, output) && outputRef.isAssetType && output.isAsset
        )
        .map(_.asUnsafe[(AssetOutputRef, AssetOutput)])
    }

    def getContractOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int
    ): IOResult[AVector[(ContractOutputRef, ContractOutput)]] = {
      outputState
        .getAll(
          outputRefPrefix,
          maxOutputs,
          (outputRef, output) => outputRef.isContractType && output.isContract
        )
        .map(_.asUnsafe[(ContractOutputRef, ContractOutput)])
    }

    def getContractState(key: Hash): IOResult[ContractState] = {
      contractState.get(key)
    }

    def getContractStates(): IOResult[AVector[(ContractId, ContractState)]] = {
      contractState.getAll(ByteString.empty, Int.MaxValue)
    }

    def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    private[WorldState] def putOutput(
        outputRef: TxOutputRef,
        output: TxOutput
    ): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState))
    }

    def createContractUnsafe(
        code: StatefulContract.HalfDecoded,
        initialStateHash: Hash,
        fields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      val state = ContractState.unsafe(code, initialStateHash, fields, outputRef)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(outputRef.key, state)
      } yield Persisted(newOutputState, newContractState)
    }

    def updateContract(key: Hash, state: ContractState): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _))
    }

    def updateContract(
        key: Hash,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      for {
        state            <- getContractState(key)
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(key, state.updateOutputRef(outputRef))
      } yield Persisted(newOutputState, newContractState)
    }

    def removeAsset(outputRef: TxOutputRef): IOResult[Persisted] = {
      outputState.remove(outputRef).map(Persisted(_, contractState))
    }

    def removeContract(contractKey: Hash): IOResult[Persisted] = {
      for {
        state            <- getContractState(contractKey)
        newOutputState   <- outputState.remove(state.contractOutputRef)
        newContractState <- contractState.remove(contractKey)
      } yield Persisted(newOutputState, newContractState)
    }

    def persist(): IOResult[WorldState.Persisted] = Right(this)

    def cached(): WorldState.Cached = {
      val outputStateCache    = CachedSMT.from(outputState)
      val contractOutputCache = CachedSMT.from(contractState)
      Cached(outputStateCache, contractOutputCache)
    }

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash)
  }

  sealed abstract class AbstractCached extends MutableWorldState {
    def outputState: MutableTrie[TxOutputRef, TxOutput]
    def contractState: MutableTrie[Hash, ContractState]

    def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
      outputState.get(outputRef)
    }

    def getOutputOpt(outputRef: TxOutputRef): IOResult[Option[TxOutput]] = {
      outputState.getOpt(outputRef)
    }

    def existOutput(outputRef: TxOutputRef): IOResult[Boolean] = {
      outputState.exist(outputRef)
    }

    def getContractState(key: Hash): IOResult[ContractState] = {
      contractState.get(key)
    }

    def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Unit] = {
      outputState.put(outputRef, output)
    }

    def createContractUnsafe(
        code: StatefulContract.HalfDecoded,
        initialStateHash: Hash,
        fields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      val state = ContractState.unsafe(code, initialStateHash, fields, outputRef)
      for {
        _ <- outputState.put(outputRef, output)
        _ <- contractState.put(outputRef.key, state)
      } yield ()
    }

    def updateContract(key: Hash, state: ContractState): IOResult[Unit] = {
      contractState.put(key, state)
    }

    def updateContract(
        key: Hash,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      for {
        state <- getContractState(key)
        _     <- outputState.put(outputRef, output)
        _     <- contractState.put(key, state.updateOutputRef(outputRef))
      } yield ()
    }

    def removeAsset(outputRef: TxOutputRef): IOResult[Unit] = {
      outputState.remove(outputRef)
    }

    def removeContract(contractKey: Hash): IOResult[Unit] = {
      for {
        state <- getContractState(contractKey)
        _     <- outputState.remove(state.contractOutputRef)
        _     <- contractState.remove(contractKey)
      } yield ()
    }

    def removeContractState(contractId: ContractId): IOResult[Unit] = {
      contractState.remove(contractId)
    }

    // Not supported, use persisted worldstate instead
    def getAssetOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int,
        predicate: (TxOutputRef, TxOutput) => Boolean
    ): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = ???
  }

  final case class Cached(
      outputState: CachedSMT[TxOutputRef, TxOutput],
      contractState: CachedSMT[Hash, ContractState]
  ) extends AbstractCached {
    def persist(): IOResult[Persisted] = {
      for {
        outputStateNew   <- outputState.persist()
        contractStateNew <- contractState.persist()
      } yield Persisted(outputStateNew, contractStateNew)
    }

    def staging(): Staging = Staging(outputState.staging(), contractState.staging())
  }

  final case class Staging(
      outputState: StagingSMT[TxOutputRef, TxOutput],
      contractState: StagingSMT[Hash, ContractState]
  ) extends AbstractCached {
    def commit(): Unit = {
      outputState.commit()
      contractState.commit()
    }

    def rollback(): Unit = {
      outputState.rollback()
      contractState.rollback()
    }

    def persist(): IOResult[Persisted] = ??? // should not be called
  }

  def emptyPersisted(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Persisted = {
    val genesisRef  = ContractOutputRef.forSMT
    val emptyOutput = TxOutput.forSMT
    val emptyOutputTrie =
      SparseMerkleTrie.build[TxOutputRef, TxOutput](storage, genesisRef, emptyOutput)
    val emptyState =
      ContractState.unsafe(StatefulContract.forSMT, Hash.zero, AVector.empty, genesisRef)
    val emptyContractTrie = SparseMerkleTrie.build(storage, Hash.zero, emptyState)
    Persisted(emptyOutputTrie, emptyContractTrie)
  }

  def emptyCached(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Cached = {
    emptyPersisted(storage).cached()
  }

  final case class Hashes(outputStateHash: Hash, contractStateHash: Hash) {
    def toPersistedWorldState(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Persisted = {
      val outputState   = SparseMerkleTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = SparseMerkleTrie[Hash, ContractState](contractStateHash, storage)
      Persisted(outputState, contractState)
    }

    def toCachedWorldState(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Cached = {
      toPersistedWorldState(storage).cached()
    }

    def stateHash: Hash = Hash.hash(outputStateHash.bytes ++ contractStateHash.bytes)
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct2(Hashes.apply, t => t.outputStateHash -> t.contractStateHash)
  }
}
