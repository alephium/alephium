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

package org.alephium.api.model

import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model.{Address, ChainIndex, ContractId}
import org.alephium.protocol.vm.LogStates
import org.alephium.util.AVector

final case class Events(
    chainFrom: Int,
    chainTo: Int,
    events: AVector[Event]
)

final case class Event(
    blockHash: BlockHash,
    contractAddress: Address.Contract,
    txId: Hash,
    eventIndex: Int,
    fields: AVector[Val]
) {
  def contractId: ContractId = contractAddress.contractId
}

object Events {
  def from(logStates: LogStates): AVector[Event] = {
    logStates.states.map { logState =>
      Event(
        logStates.blockHash,
        Address.contract(logStates.contractId),
        logState.txId,
        logState.index.toInt,
        logState.fields.map(Val.from)
      )
    }
  }

  def from(chainIndex: ChainIndex, logStates: LogStates): Events = {
    Events(chainIndex.from.value, chainIndex.to.value, Events.from(logStates))
  }

  def empty(chainIndex: ChainIndex): Events = {
    Events(chainIndex.from.value, chainIndex.to.value, events = AVector.empty)
  }
}
