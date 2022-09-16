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

import org.alephium.crypto.Byte32
import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{BlockHash, ContractId, TransactionId}
import org.alephium.protocol.vm.{LogState, LogStateRef, LogStates, LogStatesId, Val}
import org.alephium.protocol.vm.event.CachedLog
import org.alephium.util.{AlephiumSpec, AVector, I256}

class LogUtilsSpec extends AlephiumSpec {
  trait Fixture extends FlowFixture {
    val blockHash  = BlockHash.random
    val txId       = TransactionId.random
    val contractId = ContractId.random
    val fields     = AVector[Val](Val.I256(I256.from(2)), Val.True, Val.False)
    val logState   = LogState(txId, 2.toByte, fields.tail)
    val logStates  = LogStates(blockHash, contractId, AVector(logState))
    val logId      = LogStatesId(contractId, 0)
    val logRef     = LogStateRef(logId, 0)

    val logStorage = blockFlow.logStorage
    val cachedLog  = CachedLog.from(logStorage)
    cachedLog.putLog(blockHash, txId, contractId, fields, true, true) isE ()
    cachedLog.persist().isRight is true
    logStorage.logState.get(logId) isE logStates
    logStorage.logRefState.get(Byte32.unsafe(txId.bytes)) isE AVector(logRef)
    logStorage.logRefState.get(Byte32.unsafe(blockHash.bytes)) isE AVector(logRef)
  }

  it should "get logs from log storage" in new Fixture {
    blockFlow.getEvents(contractId, 0, 1) isE (1, AVector(logStates))
    blockFlow.getEvents(contractId, 1, 2) isE (1, AVector.empty[LogStates])
    blockFlow.getEvents(ContractId.random, 0, 1) isE (0, AVector.empty[LogStates])

    blockFlow.getEventsCurrentCount(contractId) isE Some(1)
    blockFlow.getEventsCurrentCount(ContractId.random) isE None

    blockFlow.getEventsByHash(Byte32.unsafe(blockHash.bytes)) isE
      AVector((blockHash, logRef, logState))
    blockFlow.getEventsByHash(Byte32.unsafe(txId.bytes)) isE
      AVector((blockHash, logRef, logState))
  }

  it should "fail when log refs are invalid" in new Fixture {
    val hash  = Byte32.generate
    val refId = ContractId.random
    logStorage.logRefState.put(
      hash,
      AVector(logRef.copy(id = logId.copy(contractId = refId)))
    ) isE ()

    blockFlow.getEventsByHash(hash).leftValue.getMessage is
      s"org.alephium.util.AppException: Key LogStatesId(ContractId(hex\"${refId.toHexString}\"),0) not found in LogUtils.getEventByRef"
  }

  it should "fail when log offsets are invalid" in new Fixture {
    val hash = Byte32.unsafe(blockHash.bytes)
    logStorage.logRefState.put(hash, AVector(logRef.copy(offset = 1))) isE ()

    blockFlow.getEventsByHash(hash).leftValue.getMessage is
      s"java.lang.Throwable: Invalid log ref: LogStateRef(LogStatesId(ContractId(hex\"${contractId.toHexString}\"),0),1)"
  }
}
