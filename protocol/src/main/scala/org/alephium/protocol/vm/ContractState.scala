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

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{ContractId, ContractOutputRef}
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait ContractStorageState

object ContractStorageState {
  implicit private[vm] val fieldsSerde: Serde[AVector[Val]] = avectorSerde[Val]
  implicit val serde: Serde[ContractStorageState] = new Serde[ContractStorageState] {
    override def serialize(input: ContractStorageState): ByteString = {
      input match {
        case s: ContractLegacyState  => ContractLegacyState.serde.serialize(s)
        case s: ContractMutableState => ContractMutableState.serde.serialize(s)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[ContractStorageState]] = {
      ContractMutableState.serde._deserialize(input) match {
        case _: Left[_, _] => ContractLegacyState.serde._deserialize(input)
        case Right(result) => Right(result)
      }
    }
  }
}

sealed trait ContractState {
  def codeHash: Hash
  def initialStateHash: Hash
  def immFields: AVector[Val]
  def mutFields: AVector[Val]
  def contractOutputRef: ContractOutputRef

  def toObject(
      contractId: ContractId,
      code: StatefulContract.HalfDecoded
  ): StatefulContractObject = {
    StatefulContractObject.unsafe(
      codeHash,
      code,
      initialStateHash,
      immFields,
      mutFields,
      contractId
    )
  }

  def updateMutFieldsUnsafe(newMutFields: AVector[Val]): ContractStorageState

  def updateOutputRef(ref: ContractOutputRef): ContractStorageState
}

object ContractState {}

final case class ContractLegacyState private (
    codeHash: Hash,
    initialStateHash: Hash,
    mutFields: AVector[Val],
    contractOutputRef: ContractOutputRef
) extends ContractStorageState
    with ContractState {
  def immFields: AVector[Val] = ContractLegacyState.emptyImmFields

  def updateMutFieldsUnsafe(newMutFields: AVector[Val]): ContractStorageState = {
    this.copy(mutFields = newMutFields)
  }

  def updateOutputRef(ref: ContractOutputRef): ContractStorageState = {
    this.copy(contractOutputRef = ref)
  }
}

final case class ContractNewState(
    immutable: ContractImmutableState,
    mutable: ContractMutableState
) extends ContractState {
  def codeHash: Hash                       = immutable.codeHash
  def initialStateHash: Hash               = immutable.initialStateHash
  def immutableStateHash: Hash             = mutable.immutableStateHash
  def immFields: AVector[Val]              = immutable.immFields
  def mutFields: AVector[Val]              = mutable.mutFields
  def contractOutputRef: ContractOutputRef = mutable.contractOutputRef

  def updateMutFieldsUnsafe(newMutFields: AVector[Val]): ContractStorageState = {
    mutable.copy(mutFields = newMutFields)
  }

  def updateOutputRef(ref: ContractOutputRef): ContractStorageState = {
    mutable.copy(contractOutputRef = ref)
  }

  def migrate(
      newCode: StatefulContract,
      newImmFields: AVector[Val],
      newMutFields: AVector[Val]
  ): ContractNewState = {
    val newImmutable = immutable.copy(codeHash = newCode.hash, immFields = newImmFields)
    val newMutable =
      mutable.copy(mutFields = newMutFields, immutableStateHash = newImmutable.stateHash)
    ContractNewState(newImmutable, newMutable)
  }
}

object ContractNewState {
  def unsafe(
      code: StatefulContract.HalfDecoded,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      contractOutputRef: ContractOutputRef
  ): ContractNewState = {
    assume(code.validate(immFields, mutFields))
    val initialStateHash = code.initialStateHash(immFields, mutFields)
    val immutableState   = ContractImmutableState(code.hash, initialStateHash, immFields)
    val mutableState = ContractMutableState(mutFields, contractOutputRef, immutableState.stateHash)
    new ContractNewState(immutableState, mutableState)
  }
}

final case class ContractMutableState private[vm] (
    mutFields: AVector[Val],
    contractOutputRef: ContractOutputRef,
    immutableStateHash: Hash
) extends ContractStorageState

object ContractMutableState {
  import ContractStorageState.fieldsSerde

  private[vm] val serde: Serde[ContractMutableState] =
    Serde.forProduct3(
      ContractMutableState.apply,
      t => (t.mutFields, t.contractOutputRef, t.immutableStateHash)
    )
}

final case class ContractImmutableState(
    codeHash: Hash,
    initialStateHash: Hash,
    immFields: AVector[Val]
) {
  def stateHash: Hash = Hash.hash(ContractImmutableState.serde.serialize(this))
}

object ContractImmutableState {
  import ContractStorageState.fieldsSerde
  implicit val serde: Serde[ContractImmutableState] =
    Serde.forProduct3(
      ContractImmutableState.apply,
      t => (t.codeHash, t.initialStateHash, t.immFields)
    )
}

object ContractLegacyState {
  val emptyImmFields: AVector[Val] = AVector.empty

  import ContractStorageState.fieldsSerde
  private[vm] val serde: Serde[ContractLegacyState] =
    Serde.forProduct4(
      ContractLegacyState.apply,
      t => (t.codeHash, t.initialStateHash, t.mutFields, t.contractOutputRef)
    )

  val forMPt: ContractLegacyState =
    ContractLegacyState(
      StatefulContract.forSMT.hash,
      Hash.zero,
      AVector.empty,
      ContractOutputRef.forSMT
    )

  def unsafe(
      code: StatefulContract.HalfDecoded,
      mutFields: AVector[Val],
      contractOutputRef: ContractOutputRef
  ): ContractLegacyState = {
    assume(code.validate(emptyImmFields, mutFields))
    val initialStateHash = code.initialStateHash(emptyImmFields, mutFields)
    new ContractLegacyState(code.hash, initialStateHash, mutFields, contractOutputRef)
  }
}
