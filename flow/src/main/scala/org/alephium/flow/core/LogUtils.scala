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

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.alephium.crypto.Byte32
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.model.{BlockHash, ContractId}
import org.alephium.protocol.vm.{LogState, LogStateRef, LogStates, LogStatesId}
import org.alephium.util.AVector

trait LogUtils { Self: FlowUtils =>

  // end is exclusive
  def getEvents(
      contractId: ContractId,
      start: Int,
      end: Int
  ): IOResult[(Int, AVector[LogStates])] = {
    assume(start < end)
    val allLogStates: ArrayBuffer[LogStates] = ArrayBuffer.empty
    var nextCount                            = start

    @tailrec
    def rec(
        logStatesId: LogStatesId
    ): IOResult[Unit] = {
      logStorage.logState.getOpt(logStatesId) match {
        case Right(Some(logStates)) =>
          allLogStates += logStates
          nextCount = logStatesId.counter + 1
          if (nextCount < end) {
            rec(LogStatesId(contractId, nextCount))
          } else {
            Right(())
          }
        case Right(None) =>
          Right(())
        case Left(error) =>
          Left(error)
      }
    }

    rec(LogStatesId(contractId, nextCount)).map(_ => (nextCount, AVector.from(allLogStates)))
  }

  // TODO: optimize this by caching contract events
  def getEventsByHash(hash: Byte32): IOResult[AVector[(BlockHash, LogStateRef, LogState)]] = {
    logStorage.logRefState.getOpt(hash) flatMap {
      case Some(logRefs) => logRefs.mapE(getEventByRef)
      case None          => Right(AVector.empty)
    }
  }

  private def getEventByRef(ref: LogStateRef): IOResult[(BlockHash, LogStateRef, LogState)] = {
    logStorage.logState.getOpt(ref.id) flatMap {
      case Some(logStates) =>
        logStates.states
          .get(ref.offset)
          .map(state => (logStates.blockHash, ref, state))
          .toRight(IOError.Other(new Throwable(s"Invalid log ref: $ref")))
      case None => Left(IOError.keyNotFound(ref.id, "LogUtils.getEventByRef"))
    }
  }

  def getEventsCurrentCount(contractId: ContractId): IOResult[Option[Int]] = {
    logStorage.logCounterState.getOpt(contractId)
  }
}
