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

package org.alephium.flow.io

import scala.collection.mutable

import org.alephium.io._
import org.alephium.protocol.vm.{LogStates, LogStatesId}

final class CachedLogStates(
    val underlying: LogStorage,
    val caches: mutable.Map[LogStatesId, Cache[LogStates]]
) extends CachedKV[LogStatesId, LogStates, Cache[LogStates]] {
  protected def getOptFromUnderlying(key: LogStatesId): IOResult[Option[LogStates]] = {
    underlying.getOpt(key).map { valueOpt =>
      valueOpt.foreach(value => caches.addOne(key -> Cached(value)))
      valueOpt
    }
  }

  def persist(): IOResult[LogStorage] = {
    underlying
      .putBatch { putAccumulate =>
        caches.foreach {
          case (_, Cached(_))         => Right(())
          case (key, Updated(value))  => putAccumulate(key, value)
          case (key, Inserted(value)) => putAccumulate(key, value)
          case (_, Removed())         => Right(())
        }
      }
      .map(_ => underlying)
  }
}
