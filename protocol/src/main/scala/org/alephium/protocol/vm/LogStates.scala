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

import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.serde.Serde
import org.alephium.util.{AVector, I256}

final case class LogStatesId(eventKey: Hash, counter: Int)

object LogStatesId {
  implicit val serde: Serde[LogStatesId] =
    Serde.forProduct2(LogStatesId.apply, id => (id.eventKey, id.counter))
}

final case class LogStateRef(id: LogStatesId, offset: Int) {
  def toFields: AVector[Val] = AVector(
    Val.ByteVec(id.eventKey.bytes),
    Val.I256(I256.from(id.counter)),
    Val.I256(I256.from(offset))
  )
}

object LogStateRef {
  private def fieldToHash(field: Val): Option[Hash] = {
    field match {
      case Val.ByteVec(bytes) => Hash.from(bytes)
      case _                  => None
    }
  }

  private def fieldToInt(field: Val): Option[Int] = {
    field match {
      case Val.I256(value) => value.toInt
      case _               => None
    }
  }

  def fromFields(fields: AVector[Val]): Option[LogStateRef] = {
    if (fields.length != 3) {
      None
    } else {
      for {
        eventKey <- fieldToHash(fields(0))
        counter  <- fieldToInt(fields(1))
        offset   <- fieldToInt(fields(2))
      } yield LogStateRef(LogStatesId(eventKey, counter), offset)
    }
  }
}

final case class LogState(
    txId: Hash,
    index: Byte,
    fields: AVector[Val]
) {
  def isRef: Boolean = index == eventRefIndex
}

object LogState {
  implicit val serde: Serde[LogState] =
    Serde.forProduct3(LogState.apply, s => (s.txId, s.index, s.fields))
}

final case class LogStates(
    blockHash: BlockHash,
    eventKey: Hash,
    states: AVector[LogState]
)

object LogStates {
  implicit val serde: Serde[LogStates] =
    Serde.forProduct3(LogStates.apply, s => (s.blockHash, s.eventKey, s.states))
}
