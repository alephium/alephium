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

import org.alephium.protocol.model.{BlockHash, ContractId, TransactionId}
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class LogStatesId(contractId: ContractId, counter: Int)

object LogStatesId {
  implicit val serde: Serde[LogStatesId] =
    Serde.forProduct2(LogStatesId.apply, id => (id.contractId, id.counter))
}

final case class LogStateRef(id: LogStatesId, offset: Int)

object LogStateRef {
  implicit val serde: Serde[LogStateRef] =
    Serde.forProduct2(LogStateRef(_, _), t => (t.id, t.offset))
}

final case class LogState(
    txId: TransactionId,
    index: Byte,
    fields: AVector[Val]
)

object LogState {
  implicit val serde: Serde[LogState] =
    Serde.forProduct3(LogState.apply, s => (s.txId, s.index, s.fields))
}

final case class LogStates(
    blockHash: BlockHash,
    contractId: ContractId,
    states: AVector[LogState]
)

object LogStates {
  implicit val serde: Serde[LogStates] =
    Serde.forProduct3(LogStates.apply, s => (s.blockHash, s.contractId, s.states))
}
