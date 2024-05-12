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

package org.alephium.protocol.vm.subcontract

import org.alephium.protocol.model.ContractId
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class SubContractIndexStateId(contractId: ContractId, counter: Int)

object SubContractIndexStateId {
  implicit val serde: Serde[SubContractIndexStateId] =
    Serde.forProduct2(SubContractIndexStateId.apply, id => (id.contractId, id.counter))
}

final case class SubContractIndexState(
    subContracts: AVector[ContractId]
)

object SubContractIndexState {
  implicit val serde: Serde[SubContractIndexState] =
    Serde.forProduct1(SubContractIndexState.apply, _.subContracts)
}
