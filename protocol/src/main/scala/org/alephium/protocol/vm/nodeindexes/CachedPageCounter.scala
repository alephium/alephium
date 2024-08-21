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

package org.alephium.protocol.vm.nodeindexes

import scala.collection.mutable

import org.alephium.io.{CachedKVStorage, IOResult, KeyValueStorage}
import org.alephium.protocol.vm.nodeindexes.PageCounter

final class CachedPageCounter[K](
    val counter: CachedKVStorage[K, Int],
    val initialCounts: mutable.Map[K, Int]
) extends PageCounter[K] {
  def getInitialCount(key: K): IOResult[Int] = {
    initialCounts.get(key) match {
      case Some(value) =>
        Right(value)
      case None =>
        counter.getOpt(key).map { countOpt =>
          val count = countOpt.getOrElse(0)
          initialCounts.put(key, count)
          count
        }
    }
  }

  def persist(): IOResult[Unit] = counter.persist().map(_ => ())

  def staging(): StagingPageCounter[K] = {
    new StagingPageCounter(this.counter.staging(), this)
  }
}

object CachedPageCounter {
  def from[K](storage: KeyValueStorage[K, Int]): CachedPageCounter[K] = {
    new CachedPageCounter[K](CachedKVStorage.from(storage), mutable.HashMap.empty)
  }
}
