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
    StatefulContractObject.unsafe(codeHash, code, initialStateHash, mutFields, contractId)
  }

  def updateMutFieldsUnsafe(newMutFields: AVector[Val]): ContractState

  def updateOutputRef(ref: ContractOutputRef): ContractState

  def migrate(newCode: StatefulContract, newFields: AVector[Val]): ContractState
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

  def updateMutFieldsUnsafe(newMutFields: AVector[Val]): ContractState = {
    this.copy(mutFields = newMutFields)
  }

  def updateOutputRef(ref: ContractOutputRef): ContractState = {
    this.copy(contractOutputRef = ref)
  }

  def migrate(newCode: StatefulContract, newFields: AVector[Val]): ContractState = {
    this.copy(codeHash = newCode.hash, mutFields = newFields)
  }
}

final case class ContractNewState(
    immutable: ContractImmutableState,
    mutable: ContractMutableState
) extends ContractState {
  def codeHash: Hash                       = immutable.codeHash
  def initialStateHash: Hash               = immutable.initialStateHash
  def immFields: AVector[Val]              = immutable.immFields
  def mutFields: AVector[Val]              = mutable.mutFields
  def contractOutputRef: ContractOutputRef = mutable.contractOutputRef

  def updateMutFieldsUnsafe(newMutFields: AVector[Val]): ContractState = {
    val newMutable = mutable.copy(mutFields = newMutFields)
    ContractNewState(immutable, newMutable)
  }

  def updateOutputRef(ref: ContractOutputRef): ContractState = {
    val newMutable = mutable.copy(contractOutputRef = ref)
    ContractNewState(immutable, newMutable)
  }

  def migrate(newCode: StatefulContract, newFields: AVector[Val]): ContractState = {
    ???
  }
}

final case class ContractMutableState private[vm] (
    mutFields: AVector[Val],
    contractOutputRef: ContractOutputRef
) extends ContractStorageState

object ContractMutableState {
  import ContractStorageState.fieldsSerde

  private[vm] val serde: Serde[ContractMutableState] =
    Serde.forProduct2(
      ContractMutableState.apply,
      t => (t.mutFields, t.contractOutputRef)
    )
}

final case class ContractImmutableState(
    codeHash: Hash,
    initialStateHash: Hash,
    immFields: AVector[Val]
)

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
      fields: AVector[Val],
      contractOutputRef: ContractOutputRef
  ): ContractLegacyState = {
    assume(code.validate(fields))
    val initialStateHash = code.initialStateHash(fields)
    new ContractLegacyState(code.hash, initialStateHash, fields, contractOutputRef)
  }
}
