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

package org.alephium.flow.network.sync

import org.alephium.util.{Cache, Duration, TimeStamp}

object FetchState {
  final case class State(timestamp: TimeStamp, downloadTimes: Int)

  def apply[T](cacheCapacity: Int, timeout: Duration, maxDownloadTimes: Int): FetchState[T] =
    new FetchState[T](cacheCapacity, timeout, maxDownloadTimes)
}

final class FetchState[T](cacheCapacity: Int, timeout: Duration, maxDownloadTimes: Int) {
  val states: Cache[T, FetchState.State] = Cache.fifo(cacheCapacity, _.timestamp, timeout)

  def needToFetch(inventory: T, timestamp: TimeStamp): Boolean = {
    states.get(inventory) match {
      case Some(state) if state.downloadTimes < maxDownloadTimes =>
        states.put(inventory, FetchState.State(timestamp, state.downloadTimes + 1))
        true
      case None =>
        states.put(inventory, FetchState.State(timestamp, 1))
        true
      case _ => false
    }
  }
}
