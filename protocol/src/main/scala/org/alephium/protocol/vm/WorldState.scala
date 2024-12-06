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
import org.alephium.protocol.vm.event.MutableLog
import org.alephium.protocol.vm.nodeindexes.{
  CachedNodeIndexes,
  NodeIndexesStorage,
  StagingNodeIndexes,
  TxOutputRefIndexStorage
}
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage.TxIdBlockHashes
import org.alephium.serde.{intSerde, Serde, SerdeError}
import org.alephium.util.{AVector, SizedLruCache}

// scalastyle:off number.of.methods file.size.limit
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

  private[vm] def getLegacyContractCode(codeHash: Hash): IOResult[Option[StatefulContract]] = {
    codeState.getOpt(codeHash).flatMap {
      case Some(record) => record.code.toContract().map(Some.apply).left.map(IOError.Serde.apply)
      case None         => Right(None)
    }
  }

  def getContractCode(codeHash: Hash): IOResult[Option[StatefulContract]] = {
    contractImmutableState.getOpt(codeHash).flatMap {
      case Some(Right(code)) => code.toContract().map(Some.apply).left.map(IOError.Serde.apply)
      case Some(Left(_)) =>
        Left(IOError.Other(new IllegalArgumentException("Invalid contract code hash")))
      case None => getLegacyContractCode(codeHash)
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

  def addAsset(
      outputRef: TxOutputRef,
      output: TxOutput,
      txId: TransactionId,
      blockHash: Option[BlockHash]
  ): IOResult[T]

  // scalastyle:off parameter.number
  def createContractUnsafe(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput,
      isLemanActivated: Boolean,
      txId: TransactionId,
      blockHash: Option[BlockHash]
  ): IOResult[T] = {
    if (isLemanActivated) {
      createContractLemanUnsafe(
        contractId,
        code,
        immFields,
        mutFields,
        outputRef,
        output,
        txId,
        blockHash
      )
    } else {
      assume(immFields.isEmpty)
      createContractLegacyUnsafe(contractId, code, mutFields, outputRef, output, txId, blockHash)
    }
  }
  // scalastyle:on parameter.number

  def createContractLegacyUnsafe(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput,
      txId: TransactionId,
      blockHash: Option[BlockHash]
  ): IOResult[T]

  def createContractLemanUnsafe(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput,
      txId: TransactionId,
      blockHash: Option[BlockHash]
  ): IOResult[T]

  def updateContractUnsafe(key: ContractId, fields: AVector[Val]): IOResult[T]

  def updateContract(
      key: ContractId,
      outputRef: ContractOutputRef,
      output: ContractOutput,
      txId: TransactionId,
      blockHash: Option[BlockHash]
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
      errorIfExceedMaxUtxos: Boolean,
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
      output: ContractOutput,
      txId: TransactionId,
      blockHash: Option[BlockHash]
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
  val assetTrieCache: SizedLruCache[Hash, SparseMerkleTrie.Node] =
    SparseMerkleTrie.nodeCache(20_000_000)
  val contractTrieCache: SizedLruCache[Hash, SparseMerkleTrie.Node] =
    SparseMerkleTrie.nodeCache(10_000_000)

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
      nodeIndexesStorage: NodeIndexesStorage
  ) extends ImmutableWorldState {
    def getAssetOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int,
        errorIfExceedMaxUtxos: Boolean,
        predicate: (TxOutputRef, TxOutput) => Boolean
    ): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
      outputState
        .getAll(
          outputRefPrefix,
          maxOutputs,
          errorIfExceedMaxUtxos,
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
          errorIfExceedMaxNodes = false,
          (outputRef, output) => outputRef.isContractType && output.isContract
        )
        .map(_.asUnsafe[(ContractOutputRef, ContractOutput)])
    }

    def getContractStates(): IOResult[AVector[(ContractId, ContractStorageState)]] = {
      contractState.getAll(ByteString.empty, Int.MaxValue)
    }

    def addAsset(
        outputRef: TxOutputRef,
        output: TxOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Persisted] = {
      for {
        updatedOutputState <- updateOutputState(outputRef, output, txId, blockHash)
      } yield {
        Persisted(
          updatedOutputState,
          contractState,
          contractImmutableState,
          codeState,
          nodeIndexesStorage
        )
      }
    }

    def createContractLegacyUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        fields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Persisted] = {
      val state = ContractLegacyState.unsafe(code, fields, outputRef)
      for {
        newOutputState   <- updateOutputState(outputRef, output, txId, blockHash)
        newContractState <- contractState.put(contractId, state)
        recordOpt        <- codeState.getOpt(code.hash)
        newCodeState     <- codeState.put(code.hash, CodeRecord.from(code, recordOpt))
      } yield Persisted(
        newOutputState,
        newContractState,
        contractImmutableState,
        newCodeState,
        nodeIndexesStorage
      )
    }

    def createContractLemanUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        immFields: AVector[Val],
        mutFields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Persisted] = {
      val state = ContractNewState.unsafe(code, immFields, mutFields, outputRef)
      for {
        newOutputState   <- updateOutputState(outputRef, output, txId, blockHash)
        newContractState <- contractState.put(contractId, state.mutable)
        _ <- contractImmutableState.put(state.mutable.immutableStateHash, Left(state.immutable))
        _ <- contractImmutableState.put(state.codeHash, Right(code))
      } yield Persisted(
        newOutputState,
        newContractState,
        contractImmutableState,
        codeState,
        nodeIndexesStorage
      )
    }

    @inline private def _updateContract(key: ContractId, state: ContractStorageState) = {
      contractState.put(key, state)
    }

    def updateContract(key: ContractId, state: ContractStorageState): IOResult[Persisted] = {
      _updateContract(key, state)
        .map(
          Persisted(
            outputState,
            _,
            contractImmutableState,
            codeState,
            nodeIndexesStorage
          )
        )
    }

    def updateContract(
        key: ContractId,
        outputRef: ContractOutputRef,
        output: ContractOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Persisted] = {
      for {
        state            <- getContractState(key)
        newOutputState   <- updateOutputState(outputRef, output, txId, blockHash)
        newContractState <- _updateContract(key, state.updateOutputRef(outputRef))
      } yield Persisted(
        newOutputState,
        newContractState,
        contractImmutableState,
        codeState,
        nodeIndexesStorage
      )
    }

    // Do not remove TxOutputRef index here to support querying historical TxOutputRefs
    def removeAsset(outputRef: TxOutputRef): IOResult[Persisted] = {
      outputState
        .remove(outputRef)
        .map(
          Persisted(
            _,
            contractState,
            contractImmutableState,
            codeState,
            nodeIndexesStorage
          )
        )
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
        nodeIndexesStorage
      )
    }

    def persist(): IOResult[WorldState.Persisted] = Right(this)

    def cached(): WorldState.Cached = {
      val outputStateCache            = CachedSMT.from(outputState)
      val contractStateCache          = CachedSMT.from(contractState)
      val contractImmutableStateCache = CachedKVStorage.from(contractImmutableState)
      val codeStateCache              = CachedSMT.from(codeState)
      val cachedNodeIndexes           = CachedNodeIndexes.from(nodeIndexesStorage)
      Cached(
        outputStateCache,
        contractStateCache,
        contractImmutableStateCache,
        codeStateCache,
        cachedNodeIndexes
      )
    }

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash, codeState.rootHash)

    private def updateOutputState(
        outputRef: TxOutputRef,
        output: TxOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[SparseMerkleTrie[TxOutputRef, TxOutput]] = {
      for {
        updateOutputState <- outputState.put(outputRef, output)
        _ <- nodeIndexesStorage.txOutputRefIndexStorage.store(
          outputRef.key,
          txId,
          blockHash
        )
      } yield updateOutputState
    }
  }

  sealed abstract class AbstractCached extends MutableWorldState {
    def outputState: MutableKV[TxOutputRef, TxOutput, Unit]
    def contractState: MutableKV[ContractId, ContractStorageState, Unit]
    def contractImmutableState: MutableKV[Hash, ContractStorageImmutableState, Unit]
    def codeState: MutableKV[Hash, CodeRecord, Unit]
    def logState: MutableLog
    def txOutputRefIndexState: TxOutputRefIndexStorage[
      MutableKV[TxOutputRef.Key, TxIdBlockHashes, Unit]
    ]

    def addAsset(
        outputRef: TxOutputRef,
        output: TxOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Unit] = {
      for {
        _ <- outputState.put(outputRef, output)
        _ <- txOutputRefIndexState.store(outputRef.key, txId, blockHash)
      } yield ()
    }

    def createContractLegacyUnsafe(
        contractId: ContractId,
        code: StatefulContract.HalfDecoded,
        mutFields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Unit] = {
      val state = ContractLegacyState.unsafe(code, mutFields, outputRef)
      for {
        _         <- addAsset(outputRef, output, txId, blockHash)
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
        output: ContractOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Unit] = {
      val state = ContractNewState.unsafe(code, immFields, mutFields, outputRef)
      for {
        _ <- addAsset(outputRef, output, txId, blockHash)
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
        output: ContractOutput,
        txId: TransactionId,
        blockHash: Option[BlockHash]
    ): IOResult[Unit] = {
      for {
        state <- getContractState(key)
        _     <- addAsset(outputRef, output, txId, blockHash)
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
        errorIfExceedMaxUtxos: Boolean,
        predicate: (TxOutputRef, TxOutput) => Boolean
    ): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = ???
  }

  final case class Cached(
      outputState: CachedSMT[TxOutputRef, TxOutput],
      contractState: CachedSMT[ContractId, ContractStorageState],
      contractImmutableState: CachedKVStorage[Hash, ContractStorageImmutableState],
      codeState: CachedSMT[Hash, CodeRecord],
      nodeIndexesState: CachedNodeIndexes
  ) extends AbstractCached {
    def logState: MutableLog = nodeIndexesState.logStorageCache
    def txOutputRefIndexState: TxOutputRefIndexStorage[
      CachedKVStorage[TxOutputRef.Key, TxIdBlockHashes]
    ] =
      nodeIndexesState.txOutputRefIndexCache

    def persist(): IOResult[Persisted] = {
      for {
        outputStateNew            <- outputState.persist()
        contractStateNew          <- contractState.persist()
        contractImmutableStateNew <- contractImmutableState.persist()
        codeStateNew              <- codeState.persist()
        nodeIndexesStateNew       <- nodeIndexesState.persist()
      } yield Persisted(
        outputStateNew,
        contractStateNew,
        contractImmutableStateNew,
        codeStateNew,
        nodeIndexesStateNew
      )
    }

    def staging(): Staging =
      Staging(
        outputState.staging(),
        contractState.staging(),
        contractImmutableState.staging(),
        codeState.staging(),
        nodeIndexesState.staging()
      )
  }

  final case class Staging(
      outputState: StagingSMT[TxOutputRef, TxOutput],
      contractState: StagingSMT[ContractId, ContractStorageState],
      contractImmutableState: StagingKVStorage[Hash, ContractStorageImmutableState],
      codeState: StagingSMT[Hash, CodeRecord],
      nodeIndexesState: StagingNodeIndexes
  ) extends AbstractCached {
    def logState: MutableLog = nodeIndexesState.logState
    def txOutputRefIndexState: TxOutputRefIndexStorage[
      StagingKVStorage[TxOutputRef.Key, TxIdBlockHashes]
    ] =
      nodeIndexesState.txOutputRefIndexState

    def commit(): Unit = {
      outputState.commit()
      contractState.commit()
      contractImmutableState.commit()
      codeState.commit()
      nodeIndexesState.commit()
    }

    def rollback(): Unit = {
      outputState.rollback()
      contractState.rollback()
      contractImmutableState.rollback()
      codeState.rollback()
      nodeIndexesState.rollback()
    }

    def persist(): IOResult[Persisted] = ??? // should not be called
  }

  def emptyPersisted(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
      nodeIndexesStorage: NodeIndexesStorage
  ): Persisted = {
    val genesisRef  = ContractOutputRef.forSMT
    val emptyOutput = TxOutput.forSMT
    val emptyOutputTrie = SparseMerkleTrie.unsafe[TxOutputRef, TxOutput](
      trieStorage,
      genesisRef,
      emptyOutput,
      assetTrieCache
    )
    val emptyState: ContractStorageState =
      ContractLegacyState.unsafe(StatefulContract.forSMT, AVector.empty, genesisRef)
    val emptyCode = CodeRecord(StatefulContract.forSMT, 0)
    val emptyContractTrie =
      SparseMerkleTrie.unsafe(trieStorage, ContractId.zero, emptyState, contractTrieCache)
    val emptyCodeTrie =
      SparseMerkleTrie.unsafe(trieStorage, Hash.zero, emptyCode, contractTrieCache)
    Persisted(
      emptyOutputTrie,
      emptyContractTrie,
      trieImmutableStateStorage,
      emptyCodeTrie,
      nodeIndexesStorage
    )
  }

  def emptyCached(
      trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
      trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
      nodeIndexesStorage: NodeIndexesStorage
  ): Cached = {
    emptyPersisted(
      trieStorage,
      trieImmutableStateStorage,
      nodeIndexesStorage
    )
      .cached()
  }

  final case class Hashes(outputStateHash: Hash, contractStateHash: Hash, codeStateHash: Hash) {
    def toPersistedWorldState(
        trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
        trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
        nodeIndexesStorage: NodeIndexesStorage
    ): Persisted = {
      val outputState =
        SparseMerkleTrie[TxOutputRef, TxOutput](outputStateHash, trieStorage, assetTrieCache)
      val contractState = SparseMerkleTrie[ContractId, ContractStorageState](
        contractStateHash,
        trieStorage,
        contractTrieCache
      )
      val codeState =
        SparseMerkleTrie[Hash, CodeRecord](codeStateHash, trieStorage, contractTrieCache)
      Persisted(
        outputState,
        contractState,
        trieImmutableStateStorage,
        codeState,
        nodeIndexesStorage
      )
    }

    def toCachedWorldState(
        trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
        trieImmutableStateStorage: KeyValueStorage[Hash, ContractStorageImmutableState],
        nodeIndexesStorage: NodeIndexesStorage
    ): Cached = {
      toPersistedWorldState(
        trieStorage,
        trieImmutableStateStorage,
        nodeIndexesStorage
      ).cached()
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
