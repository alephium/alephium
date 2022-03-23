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

package org.alephium.flow.core

import org.alephium.io.IOResult
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model.ChainIndex
import org.alephium.protocol.vm.LogStates
import org.alephium.protocol.vm.LogStatesId

trait LogUtils { Self: FlowUtils =>
  def getEvents(blockHash: BlockHash, eventKey: Hash): IOResult[Option[LogStates]] = {
    val chainIndex  = ChainIndex.from(blockHash)
    val logStatesId = LogStatesId(blockHash, eventKey)

    for {
      worldState   <- blockFlow.getBestPersistedWorldState(chainIndex.from)
      logStatesOpt <- worldState.logState.getOpt(logStatesId)
    } yield logStatesOpt
  }
}
