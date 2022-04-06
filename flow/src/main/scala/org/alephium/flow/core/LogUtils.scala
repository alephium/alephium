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

import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.model.ChainIndex
import org.alephium.protocol.vm.{LogStates, LogStatesId, WorldState}
import org.alephium.util.AVector

trait LogUtils { Self: FlowUtils =>

  def getEvents(
      chainIndex: ChainIndex,
      eventKey: Hash,
      start: Int,
      endOpt: Option[Int]
  ): IOResult[AVector[LogStates]] = {
    var allLogStates: Seq[LogStates] = Seq.empty

    @tailrec
    def rec(worldState: WorldState.Persisted, logStatesId: LogStatesId): IOResult[Unit] = {
      worldState.logState.getOpt(logStatesId) match {
        case Right(Some(logStates)) =>
          assume(logStates.states.nonEmpty)
          val newCounter = logStatesId.counter + logStates.states.length
          endOpt match {
            case None =>
              allLogStates = allLogStates :+ logStates
              rec(worldState, LogStatesId(eventKey, newCounter))
            case Some(end) =>
              if (end < newCounter) {
                allLogStates = allLogStates :+ logStates.copy(
                  states = logStates.states.take(end - logStatesId.counter)
                )
                Right(())
              } else {
                rec(worldState, LogStatesId(eventKey, newCounter))
              }
          }
        case Right(None) =>
          Right(())
        case Left(error) =>
          Left(error)
      }
    }

    for {
      worldState <- blockFlow.getBestPersistedWorldState(chainIndex.from)
      _          <- rec(worldState, LogStatesId(eventKey, start))
    } yield AVector.from(allLogStates)
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
