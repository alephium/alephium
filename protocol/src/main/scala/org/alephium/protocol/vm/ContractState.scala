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

import org.alephium.protocol.Hash
import org.alephium.protocol.model.ContractOutputRef
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class ContractState private (
    codeHash: Hash,
    initialStateHash: Hash,
    fields: AVector[Val],
    contractOutputRef: ContractOutputRef
) {
  def updateFieldsUnsafe(newFields: AVector[Val]): ContractState = {
    this.copy(fields = newFields)
  }

  def updateOutputRef(ref: ContractOutputRef): ContractState = {
    this.copy(contractOutputRef = ref)
  }

  def toObject(address: Hash, code: StatefulContract.HalfDecoded): StatefulContractObject = {
    StatefulContractObject(code, initialStateHash, fields, address)
  }
}

object ContractState {
  implicit val serde: Serde[ContractState] =
    Serde.forProduct4(
      ContractState.apply,
      t => (t.codeHash, t.initialStateHash, t.fields, t.contractOutputRef)
    )

  val forMPt: ContractState =
    ContractState(StatefulContract.forSMT.hash, Hash.zero, AVector.empty, ContractOutputRef.forSMT)

  def unsafe(
      code: StatefulContract.HalfDecoded,
      initialStateHash: Hash,
      fields: AVector[Val],
      contractOutputRef: ContractOutputRef
  ): ContractState = {
    assume(code.validate(fields))
    new ContractState(code.hash, initialStateHash, fields, contractOutputRef)
  }
}
