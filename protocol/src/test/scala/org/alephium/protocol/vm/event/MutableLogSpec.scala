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

package org.alephium.protocol.vm.event

import scala.util.Random

import org.alephium.crypto.Byte32
import org.alephium.protocol.model.{BlockHash, ContractId, TransactionId}
import org.alephium.protocol.vm.{LogState, LogStateRef, LogStates, LogStatesId, Val}
import org.alephium.util.{AlephiumSpec, AVector, I256, NumericHelpers}

class MutableLogSpec extends AlephiumSpec with Fixture with NumericHelpers {
  trait LogFixture {
    val storage    = newDBStorage()
    val cachedLog  = newCachedLog(storage)
    val stagingLog = cachedLog.staging()

    val mutableLog = if (Random.nextBoolean()) cachedLog else stagingLog

    val blockHash  = BlockHash.random
    val txId       = TransactionId.random
    val contractId = ContractId.random
    val fields     = AVector[Val](Val.I256(I256.from(2)), Val.True, Val.False)
    val logState   = LogState(txId, 2.toByte, fields.tail)
    val logStates  = LogStates(blockHash, contractId, AVector(logState))
    val logId      = LogStatesId(contractId, 0)

    def persist() = {
      mutableLog match {
        case log: CachedLog => log.persist().isRight is true
        case log: StagingLog =>
          log.commit()
          cachedLog.persist()
        case _ => ()
      }
    }
  }

  it should "extract event index" in new LogFixture {
    MutableLog.getEventIndex(fields) is Some(2.toByte)
    MutableLog.getEventIndex(fields.replace(0, Val.U256(2))) is None
  }

  it should "ignore events with invalid event index" in new LogFixture {
    mutableLog.putLog(
      blockHash,
      txId,
      contractId,
      fields.replace(0, Val.U256(2)),
      false,
      false
    ) isE ()
    mutableLog.eventLog.getOpt(logId) isE None
  }

  it should "put events" in new LogFixture {
    mutableLog.putLog(blockHash, txId, contractId, fields, false, false) isE ()
    mutableLog.eventLog.get(LogStatesId(contractId, 0)) isE logStates

    mutableLog.putLog(blockHash, txId, contractId, fields, false, false) isE ()
    mutableLog.eventLog.get(LogStatesId(contractId, 0)) isE
      logStates.copy(states = AVector(logState, logState))
  }

  it should "update initial value once persisted" in new LogFixture {
    mutableLog.putLog(blockHash, txId, contractId, fields, false, false) isE ()
    mutableLog.eventLog.get(LogStatesId(contractId, 0)) isE logStates
    mutableLog.eventLog.exists(LogStatesId(contractId, 1)) isE false

    persist()

    val newLog       = newCachedLog(storage)
    val newBlockHash = BlockHash.random
    newLog.putLog(newBlockHash, txId, contractId, fields, false, false) isE ()
    newLog.putLog(newBlockHash, txId, contractId, fields, false, false) isE ()
    newLog.eventLogPageCounter.getInitialCount(contractId) isE 1
    newLog.eventLog.get(LogStatesId(contractId, 0)) isE logStates
    newLog.eventLog.get(LogStatesId(contractId, 1)) isE
      logStates.copy(blockHash = newBlockHash, states = AVector(logState, logState))
  }

  it should "log event for tx id" in new LogFixture {
    mutableLog.putLog(blockHash, txId, contractId, fields, true, false) isE ()
    mutableLog.putLog(blockHash, txId, contractId, fields, false, false) isE ()
    mutableLog.putLog(blockHash, txId, contractId, fields, true, false) isE ()
    mutableLog.eventLogByHash.get(Byte32.unsafe(txId.bytes)) isE
      AVector(LogStateRef(logId, 0), LogStateRef(logId, 2))
    mutableLog.eventLogByHash.exists(Byte32.unsafe(blockHash.bytes)) isE false
  }

  it should "log event for block hash" in new LogFixture {
    mutableLog.putLog(blockHash, txId, contractId, fields, false, true) isE ()
    mutableLog.putLog(blockHash, txId, contractId, fields, false, false) isE ()
    mutableLog.putLog(blockHash, txId, contractId, fields, false, true) isE ()
    mutableLog.eventLogByHash.exists(Byte32.unsafe(txId.bytes)) isE false
    mutableLog.eventLogByHash.get(Byte32.unsafe(blockHash.bytes)) isE
      AVector(LogStateRef(logId, 0), LogStateRef(logId, 2))
  }

  it should "log event for both tx id and block hash" in new LogFixture {
    mutableLog.putLog(blockHash, txId, contractId, fields, true, true) isE ()
    mutableLog.putLog(blockHash, txId, contractId, fields, false, false) isE ()
    mutableLog.putLog(blockHash, txId, contractId, fields, true, true) isE ()
    mutableLog.eventLogByHash.get(Byte32.unsafe(txId.bytes)) isE
      AVector(LogStateRef(logId, 0), LogStateRef(logId, 2))
    mutableLog.eventLogByHash.get(Byte32.unsafe(blockHash.bytes)) isE
      AVector(LogStateRef(logId, 0), LogStateRef(logId, 2))
  }
}
