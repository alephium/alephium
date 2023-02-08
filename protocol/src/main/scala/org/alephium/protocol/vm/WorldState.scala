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
import org.alephium.protocol.vm.event.{CachedLog, LogStorage, MutableLog, StagingLog}
import org.alephium.serde.{Serde, SerdeError}
import org.alephium.util.AVector

// scalastyle:off number.of.methods
trait WorldState[T, R1, R2, R3] {
  def outputState: MutableKV[TxOutputRef, TxOutput, R1]
  def contractState: MutableKV[ContractId, ContractStorageState, R2]
  def contractImmutableState: MutableKV[Hash, ContractStorageImmutableState, Unit]
  def codeState: MutableKV[Hash, WorldState.CodeRecord, R3]

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

  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
    outputState.get(outputRef)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getContractOutput(contractOutputRef: ContractOutputRef): IOResult[ContractOutput] = {
    getOutput(contractOutputRef) match {
      case Right(_: AssetOutput) => Left(WorldState.expectedContractError)
      case result                => result.asInstanceOf[IOResult[ContractOutput]]
    }
  }

  def getOutputOpt(outputRef: TxOutputRef): IOResult[Option[TxOutput]] = {
    outputState.getOpt(outputRef)
  }

  def existOutput(outputRef: TxOutputRef): IOResult[Boolean] = {
    outputState.exists(outputRef)
  }

  def getContractState(id: ContractId): IOResult[ContractState] = {
    contractState.get(id).flatMap {
      case mutable: ContractMutableState =>
        contractImmutableState.get(mutable.immutableStateHash) map {
          case Left(immutable: ContractImmutableState) =>
            ContractNewState(immutable, mutable)
          case _ => throw new RuntimeException("Invalid contract state")
        }
      case s: ContractLegacyState => Right(s)
    }
  }

  def contractExists(id: ContractId): IOResult[Boolean] = {
    contractState.exists(id)
  }

  def getContractCode(state: ContractState): IOResult[StatefulContract.HalfDecoded] = {
    state match {
      case _: ContractLegacyState => codeState.get(state.codeHash).map(_.code)
      case _: ContractNewState =>
        contractImmutableState.get(state.codeHash).map {
          case Right(code) => code
          case _           => throw new RuntimeException("Invalid contract state")
        }
    }
  }

  def getContractAsset(id: ContractId): IOResult[ContractOutput] = {
    for {
      state  <- getContractState(id)
      output <- getContractAsset(state.contractOutputRef)
    } yield output
  }

  def getContractOutputInfo(id: ContractId): IOResult[(ContractOutputRef, ContractOutput)] = {
    for {
      state  <- getContractState(id)
      output <- getContractAsset(state.contractOutputRef)
    } yield state.contractOutputRef -> output
  }

  def getContractAsset(outputRef: ContractOutputRef): IOResult[ContractOutput] = {
    for {
      outputRaw <- getOutput(outputRef)
      output <- outputRaw match {
        case _: AssetOutput =>
          val error = s"ContractOutput expected, but was AssetOutput at $outputRef"
          Left(IOError.Other(new RuntimeException(error)))
        case o: ContractOutput =>
          Right(o)
      }
    } yield output
  }

  def getContractObj(key: ContractId): IOResult[StatefulContractObject] = {
    for {
      state <- getContractState(key)
      code  <- getContractCode(state)
    } yield state.toObject(key, code)
  }

  def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[T]

  def createContractUnsafe(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput,
      isLemanActivated: Boolean
  ): IOResult[T] = {
    if (isLemanActivated) {
      createContractLemanUnsafe(contractId, code, immFields, mutFields, outputRef, output)
    } else {
      assume(immFields.isEmpty)
      createContractLegacyUnsafe(contractId, code, mutFields, outputRef, output)
    }
  }

  def createContractLegacyUnsafe(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[T]

  def createContractLemanUnsafe(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[T]

  def updateContractUnsafe(key: ContractId, fields: AVector[Val]): IOResult[T]

  def updateContract(
      key: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[T]

  protected[vm] def updateContract(key: ContractId, state: ContractStorageState): IOResult[T]

  def removeAsset(outputRef: TxOutputRef): IOResult[T]

  def removeContractFromVM(contractKey: ContractId): IOResult[T]

  protected def removeContractCode(
      currentState: ContractState
  ): IOResult[R3] = {
    currentState match {
      case _: ContractLegacyState => removeContractCodeDeprecated(currentState)
      case _: ContractNewState    => Right(codeState.unit) // We keep the code as history state
    }
  }

  protected def removeContractCodeDeprecated(
      currentState: ContractState
  ): IOResult[R3] = {
    codeState.get(currentState.codeHash).flatMap { currentRecord =>
      if (currentRecord.refCount > 1) {
        codeState.put(
          currentState.codeHash,
          currentRecord.copy(refCount = currentRecord.refCount - 1)
        )
      } else {
        codeState.remove(currentState.codeHash)
      }
    }
  }

  def persist(): IOResult[WorldState.Persisted]

  def getPreOutputsForAssetInputs(
      tx: TransactionAbstract
  ): IOResult[Option[AVector[AssetOutput]]] = {
    val inputs = tx.unsigned.inputs
    inputs.foldE[IOError, Option[AVector[AssetOutput]]](Some(AVector.ofCapacity(inputs.length))) {
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
    } yield fixedInputs ++ contractInputs
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

sealed abstract class MutableWorldState extends WorldState[Unit, Unit, Unit, Unit] {
  def loadContractAssets(contractId: ContractId): IOResult[(ContractOutputRef, ContractOutput)] = {
    for {
      state  <- getContractState(contractId)
      output <- getContractAsset(state.contractOutputRef)
    } yield (state.contractOutputRef, output)
  }

  def updateContractUnsafe(key: ContractId, mutFields: AVector[Val]): IOResult[Unit] = {
    for {
      oldState <- getContractState(key)
      _        <- updateContract(key, oldState.updateMutFieldsUnsafe(mutFields))
    } yield ()
  }

  def updateContract(
      key: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[Unit]
}

// scalastyle:off no.whitespace.after.left.bracket
sealed abstract class ImmutableWorldState
    extends WorldState[
      ImmutableWorldState,
      SparseMerkleTrie[TxOutputRef, TxOutput],
      SparseMerkleTrie[ContractId, ContractStorageState],
      SparseMerkleTrie[Hash, WorldState.CodeRecord]
    ] {
  def updateContractUnsafe(key: ContractId, fields: AVector[Val]): IOResult[ImmutableWorldState] = {
    for {
      oldState      <- getContractState(key)
      newWorldState <- updateContract(key, oldState.updateMutFieldsUnsafe(fields))
    } yield newWorldState
  }
}
// scalastyle:on

object WorldState {
  val expectedAssetError: IOError    = IOError.Serde(SerdeError.validation("Expect AssetOutput"))
  val expectedContractError: IOError = IOError.Serde(SerdeError.validation("Expect ContractOutput"))

  final case class CodeRecord(code: StatefulContract.HalfDecoded, refCount: Int)
  object CodeRecord {
    implicit val serde: Serde[CodeRecord] =
      Serde.forProduct2(CodeRecord.apply, t => (t.code, t.refCount))

    def from(code: StatefulContract.HalfDecoded, recordOpt: Option[CodeRecord]): CodeRecord =
      recordOpt match {
        case Some(record) => record.copy(refCount = record.refCount + 1)
        case None         => CodeRecord(code, 1)
      }
  }

  final case class Persisted(
      outputState: SparseMerkleTrie[TxOutputRef, TxOutput],
      contractState: SparseMerkleTrie[ContractId, ContractStorageState],
      contractImmutableState: KeyValueStorage[Hash, ContractStorageImmutableState],
      codeState: SparseMerkleTrie[Hash, CodeRecord],
      logStorage: LogStorage
  ) extends ImmutableWorldState {
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

    def getContractStates(): IOResult[AVector[(ContractId, ContractStorageState)]] = {
      contractState.getAll(ByteString.empty, Int.MaxValue)
    }

    def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Persisted] = {
      outputState
        .put(outputRef, output)
        .map(Persisted(_, contractState, contractImmutableState, codeState, logStorage))
    }

    private[WorldState] def putOutput(
        outputRef: TxOutputRef,
        output: TxOutput
    ): IOResult[Persisted] = {
      outputState
        .put(outputRef, output)
        .map(Persisted(_, contractState, contractImmutableState, codeState, logStorage))
    }

    def createContractLegacyUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        fields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      val state = ContractLegacyState.unsafe(code, fields, outputRef)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(contractId, state)
        recordOpt        <- codeState.getOpt(code.hash)
        newCodeState     <- codeState.put(code.hash, CodeRecord.from(code, recordOpt))
      } yield Persisted(
        newOutputState,
        newContractState,
        contractImmutableState,
        newCodeState,
        logStorage
      )
    }

    def createContractLemanUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        immFields: AVector[Val],
        mutFields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      val state = ContractNewState.unsafe(code, immFields, mutFields, outputRef)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(contractId, state.mutable)
        _ <- contractImmutableState.put(state.mutable.immutableStateHash, Left(state.immutable))
        _ <- contractImmutableState.put(state.codeHash, Right(code))
      } yield Persisted(
        newOutputState,
        newContractState,
        contractImmutableState,
        codeState,
        logStorage
      )
    }

    @inline private def _updateContract(key: ContractId, state: ContractStorageState) = {
      contractState.put(key, state)
    }

    def updateContract(key: ContractId, state: ContractStorageState): IOResult[Persisted] = {
      _updateContract(key, state)
        .map(Persisted(outputState, _, contractImmutableState, codeState, logStorage))
    }

    def updateContract(
        key: ContractId,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      for {
        state            <- getContractState(key)
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- _updateContract(key, state.updateOutputRef(outputRef))
      } yield Persisted(
        newOutputState,
        newContractState,
        contractImmutableState,
        codeState,
        logStorage
      )
    }

    def removeAsset(outputRef: TxOutputRef): IOResult[Persisted] = {
      outputState
        .remove(outputRef)
        .map(Persisted(_, contractState, contractImmutableState, codeState, logStorage))
    }

    // Contract output is already removed by the VM
    def removeContractFromVM(contractKey: ContractId): IOResult[Persisted] = {
      for {
        state            <- getContractState(contractKey)
        newContractState <- contractState.remove(contractKey)
        newCodeState     <- removeContractCode(state)
      } yield Persisted(
        outputState,
        newContractState,
        contractImmutableState,
        newCodeState,
        logStorage
      )
    }

    def persist(): IOResult[WorldState.Persisted] = Right(this)

    def cached(): WorldState.Cached = {
      val outputStateCache            = CachedSMT.from(outputState)
      val contractStateCache          = CachedSMT.from(contractState)
      val contractImmutableStateCache = CachedKVStorage.from(contractImmutableState)
      val codeStateCache              = CachedSMT.from(codeState)
      val logStatesCache              = CachedLog.from(logStorage)
      Cached(
        outputStateCache,
        contractStateCache,
        contractImmutableStateCache,
        codeStateCache,
        logStatesCache
      )
    }

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash, codeState.rootHash)
  }

  sealed abstract class AbstractCached extends MutableWorldState {
    def outputState: MutableKV[TxOutputRef, TxOutput, Unit]
    def contractState: MutableKV[ContractId, ContractStorageState, Unit]
    def contractImmutableState: MutableKV[Hash, ContractStorageImmutableState, Unit]
    def codeState: MutableKV[Hash, CodeRecord, Unit]
    def logState: MutableLog

    def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Unit] = {
      outputState.put(outputRef, output)
    }

    def createContractLegacyUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        mutFields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      val state = ContractLegacyState.unsafe(code, mutFields, outputRef)
      for {
        _         <- outputState.put(outputRef, output)
        _         <- contractState.put(contractId, state)
        recordOpt <- codeState.getOpt(code.hash)
        _         <- codeState.put(code.hash, CodeRecord.from(code, recordOpt))
      } yield ()
    }

    def createContractLemanUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        immFields: AVector[Val],
        mutFields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      val state = ContractNewState.unsafe(code, immFields, mutFields, outputRef)
      for {
        _ <- outputState.put(outputRef, output)
        _ <- contractState.put(contractId, state.mutable)
        _ <- contractImmutableState.put(state.mutable.immutableStateHash, Left(state.immutable))
        _ <- contractImmutableState.put(state.codeHash, Right(code))
      } yield ()
    }

    def updateContract(key: ContractId, state: ContractStorageState): IOResult[Unit] = {
      contractState.put(key, state)
    }

    def updateContract(
        key: ContractId,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      for {
        state <- getContractState(key)
        _     <- outputState.put(outputRef, output)
        _     <- updateContract(key, state.updateOutputRef(outputRef))
      } yield ()
    }

    // only available since Leman fork
    def migrateContractLemanUnsafe(
        contractId: ContractId,
        newCode: StatefulContract,
        newImmFields: AVector[Val],
        newMutFields: AVector[Val]
    ): IOResult[Boolean] = {
      getContractState(contractId).flatMap {
        case s: ContractNewState =>
          migrateContractLemanUnsafe(contractId, s, newCode, newImmFields, newMutFields)
        case _: ContractLegacyState =>
          Right(false)
      }
    }

    @inline private def migrateContractLemanUnsafe(
        contractId: ContractId,
        state: ContractNewState,
        newCode: StatefulContract,
        newImmFields: AVector[Val],
        newMutFields: AVector[Val]
    ): IOResult[Boolean] = {
      val migratedState = state.migrate(newCode, newImmFields, newMutFields)
      for {
        _ <- updateContract(contractId, migratedState.mutable)
        _ <- contractImmutableState.put(
          migratedState.immutableStateHash,
          Left(migratedState.immutable)
        )
        _ <- contractImmutableState.put(newCode.hash, Right(newCode.toHalfDecoded()))
      } yield true
    }

    def removeAsset(outputRef: TxOutputRef): IOResult[Unit] = {
      outputState.remove(outputRef)
    }

    // Contract output is already removed by the VM
    def removeContractFromVM(contractId: ContractId): IOResult[Unit] = {
      for {
        state <- getContractState(contractId)
        _     <- removeContractCode(state)
        _     <- contractState.remove(contractId)
      } yield ()
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
      contractState: CachedSMT[ContractId, ContractStorageState],
      contractImmutableState: CachedKVStorage[Hash, ContractStorageImmutableState],
      codeState: CachedSMT[Hash, CodeRecord],
      logState: CachedLog
  ) extends AbstractCached {
    def persist(): IOResult[Persisted] = {
      for {
        outputStateNew            <- outputState.persist()
        contractStateNew          <- contractState.persist()
        contractImmutableStateNew <- contractImmutableState.persist()
        codeStateNew              <- codeState.persist()
        logStorage                <- logState.persist()
      } yield Persisted(
        outputStateNew,
        contractStateNew,
        contractImmutableStateNew,
        codeStateNew,
        logStorage
      )
    }

    def staging(): Staging =
      Staging(
        outputState.staging(),
        contractState.staging(),
        contractImmutableState.staging(),
        codeState.staging(),
        logState.staging()
      )
  }

  final case class Staging(
      outputState: StagingSMT[TxOutputRef, TxOutput],
      contractState: StagingSMT[ContractId, ContractStorageState],
      contractImmutableState: StagingKVStorage[Hash, ContractStorageImmutableState],
      codeState: StagingSMT[Hash, CodeRecord],
      logState: StagingLog
  ) extends AbstractCached {
    def commit(): Unit = {
      outputState.commit()
      contractState.commit()
      contractImmutableState.commit()
      codeState.commit()
      logState.commit()
    }

    def rollback(): Unit = {
      outputState.rollback()
      contractState.rollback()
      contractImmutableState.rollback()
      codeState.rollback()
      logState.rollback()
    }

    def persist(): IOResult[Persisted] = ??? // should not be called
  }

  def emptyPersisted(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
      logStorage: LogStorage
  ): Persisted = {
    val genesisRef  = ContractOutputRef.forSMT
    val emptyOutput = TxOutput.forSMT
    val emptyOutputTrie =
      SparseMerkleTrie.unsafe[TxOutputRef, TxOutput](trieStorage, genesisRef, emptyOutput)
    val emptyState: ContractStorageState =
      ContractLegacyState.unsafe(StatefulContract.forSMT, AVector.empty, genesisRef)
    val emptyCode         = CodeRecord(StatefulContract.forSMT, 0)
    val emptyContractTrie = SparseMerkleTrie.unsafe(trieStorage, ContractId.zero, emptyState)
    val emptyCodeTrie     = SparseMerkleTrie.unsafe(trieStorage, Hash.zero, emptyCode)
    Persisted(
      emptyOutputTrie,
      emptyContractTrie,
      trieImmutableStateStorage,
      emptyCodeTrie,
      logStorage
    )
  }

  def emptyCached(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
      logStorage: LogStorage
  ): Cached = {
    emptyPersisted(trieStorage, trieImmutableStateStorage, logStorage).cached()
  }

  final case class Hashes(outputStateHash: Hash, contractStateHash: Hash, codeStateHash: Hash) {
    def toPersistedWorldState(
        trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
        trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
        logStorage: LogStorage
    ): Persisted = {
      val outputState = SparseMerkleTrie[TxOutputRef, TxOutput](outputStateHash, trieStorage)
      val contractState =
        SparseMerkleTrie[ContractId, ContractStorageState](contractStateHash, trieStorage)
      val codeState = SparseMerkleTrie[Hash, CodeRecord](codeStateHash, trieStorage)
      Persisted(outputState, contractState, trieImmutableStateStorage, codeState, logStorage)
    }

    def toCachedWorldState(
        trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
        trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
        logStorage: LogStorage
    ): Cached = {
      toPersistedWorldState(trieStorage, trieImmutableStateStorage, logStorage).cached()
    }

    def stateHash: Hash = Hash.hash(outputStateHash.bytes ++ contractStateHash.bytes)
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct3(
        Hashes.apply,
        t => (t.outputStateHash, t.contractStateHash, t.codeStateHash)
      )
  }
}
