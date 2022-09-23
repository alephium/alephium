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

import org.alephium.crypto.Byte32
import org.alephium.io.{IOResult, MutableKV}
import org.alephium.protocol.model.{BlockHash, ContractId, TransactionId}
import org.alephium.protocol.vm.{LogState, LogStateRef, LogStates, LogStatesId, Val}
import org.alephium.util.AVector

trait MutableLog {
  def eventLog: MutableKV[LogStatesId, LogStates, Unit]
  def eventLogByHash: MutableKV[Byte32, AVector[LogStateRef], Unit]
  def eventLogPageCounter: MutableLog.LogPageCounter[ContractId]

  def putLog(
      blockHash: BlockHash,
      txId: TransactionId,
      contractId: ContractId,
      fields: AVector[Val],
      indexByTxId: Boolean,
      indexByBlockHash: Boolean
  ): IOResult[Unit] = {
    MutableLog.getEventIndex(fields) match {
      case Some(index) =>
        val state = LogState(txId, index, fields.tail)
        putLogByContractId(blockHash, contractId, state).flatMap { logRef =>
          for {
            _ <- if (indexByTxId) putLogIndexByTxId(txId, logRef) else Right(())
            _ <- if (indexByBlockHash) putLogIndexByBlockHash(blockHash, logRef) else Right(())
          } yield ()
        }
      case None => Right(())
    }
  }

  private[event] def putLogByContractId(
      blockHash: BlockHash,
      contractId: ContractId,
      state: LogState
  ): IOResult[LogStateRef] = {
    for {
      initialCount <- eventLogPageCounter.getInitialCount(contractId)
      id = LogStatesId(contractId, initialCount)
      logStatesOpt <- eventLog.getOpt(id)
      _ <- logStatesOpt match {
        case Some(logStates) =>
          eventLog.put(id, logStates.copy(states = logStates.states :+ state))
        case None =>
          eventLog.put(id, LogStates(blockHash, contractId, AVector(state)))
      }
      _ <- eventLogPageCounter.counter.put(contractId, initialCount + 1)
    } yield LogStateRef(id, getLogOffset(logStatesOpt))
  }

  @inline private def getLogOffset(currentLogsOpt: Option[LogStates]): Int = {
    currentLogsOpt match {
      case Some(logs) => logs.states.length
      case None       => 0
    }
  }

  @inline private[event] def putLogIndexByTxId(
      txId: TransactionId,
      logRef: LogStateRef
  ): IOResult[Unit] = {
    putLogIndexByByte32(Byte32.unsafe(txId.bytes), logRef)
  }

  @inline private[event] def putLogIndexByBlockHash(
      blockHash: BlockHash,
      logRef: LogStateRef
  ): IOResult[Unit] = {
    putLogIndexByByte32(Byte32.unsafe(blockHash.bytes), logRef)
  }

  @inline private[event] def putLogIndexByByte32(
      byte32: Byte32,
      logRef: LogStateRef
  ): IOResult[Unit] = {
    for {
      eventIndexOpt <- eventLogByHash.getOpt(byte32)
      _ <- eventIndexOpt match {
        case Some(logRefs) =>
          eventLogByHash.put(byte32, logRefs :+ logRef)
        case None =>
          eventLogByHash.put(byte32, AVector(logRef))
      }
    } yield ()
  }
}

object MutableLog {
  trait LogPageCounter[K] {
    def counter: MutableKV[K, Int, Unit]
    def getInitialCount(key: K): IOResult[Int]
  }

  @inline private[event] def getEventIndex(fields: AVector[Val]): Option[Byte] = {
    fields.headOption.flatMap {
      case Val.I256(i) => i.toByte
      case _           => None
    }
  }
}
