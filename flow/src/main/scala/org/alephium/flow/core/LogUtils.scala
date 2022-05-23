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

import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model.ChainIndex
import org.alephium.protocol.vm.{LogState, LogStateRef, LogStates, LogStatesId}
import org.alephium.util.AVector

trait LogUtils { Self: FlowUtils =>

  def getEvents(
      eventKey: Hash,
      start: Int,
      end: Int
  ): IOResult[(Int, AVector[LogStates])] = {
    val allLogStates: ArrayBuffer[LogStates] = ArrayBuffer.empty
    var nextCount                            = start

    @tailrec
    def rec(
        logStatesId: LogStatesId
    ): IOResult[Unit] = {
      logStorage.getOpt(logStatesId) match {
        case Right(Some(logStates)) =>
          assume(logStates.states.nonEmpty)
          nextCount = logStatesId.counter + 1
          if (end < nextCount) {
            Right(())
          } else {
            allLogStates += logStates
            rec(LogStatesId(eventKey, nextCount))
          }
        case Right(None) =>
          Right(())
        case Left(error) =>
          Left(error)
      }
    }

    rec(LogStatesId(eventKey, nextCount)).map(_ => (nextCount, AVector.from(allLogStates)))
  }

  def getEventByRef(
      ref: LogStateRef
  ): IOResult[(BlockHash, LogStateRef, LogState)] = {
    logStorage.getOpt(ref.id) match {
      case Right(Some(logStates)) =>
        logStates.states
          .get(ref.offset)
          .map(state => (logStates.blockHash, ref, state))
          .toRight(IOError.Other(new Throwable(s"Invalid state ref: $ref")))
      case Right(None) => Left(IOError.keyNotFound(ref.id, "LogUtils.getEventByRef"))
      case Left(error) => Left(error)
    }
  }

  def getEventsCurrentCount(
      chainIndex: ChainIndex,
      eventKey: Hash
  ): IOResult[Option[Int]] = {
    for {
      worldState <- blockFlow.getBestPersistedWorldState(chainIndex.from)
      count      <- worldState.logCounterState.getOpt(eventKey)
    } yield count
  }
}
