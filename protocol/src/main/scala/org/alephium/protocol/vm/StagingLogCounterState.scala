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

import scala.collection.mutable

import org.alephium.io._
import org.alephium.io.MutableKV.WithInitialValue
import org.alephium.protocol.Hash

final class StagingLogCounterState(
    val underlying: CachedLogCounterState,
    val caches: mutable.Map[Hash, Modified[Int]]
) extends StagingKV[Hash, Int]
    with WithInitialValue[Hash, Int, Unit] {
  override def getInitialValue(key: Hash): IOResult[Option[Int]] = {
    underlying.getInitialValue(key).flatMap { countOpt =>
      if (countOpt.isEmpty) {
        put(key, 0).map(_ => Some(0))
      } else {
        Right(countOpt)
      }
    }
  }

  override def rollback(): Unit = {
    underlying.clearInitialValues()
    super.rollback()
  }

  override def commit(): Unit = {
    underlying.clearInitialValues()
    super.commit()
  }
}
