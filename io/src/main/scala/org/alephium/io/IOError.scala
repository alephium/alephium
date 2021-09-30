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

package org.alephium.io

import org.rocksdb.RocksDBException

import org.alephium.serde.SerdeError
import org.alephium.util.AppException

sealed abstract class IOError(val reason: Throwable) extends Exception(reason)

sealed abstract class AppIOError(reason: AppException) extends IOError(reason)

object IOError {
  final case class Serde(e: SerdeError)         extends AppIOError(e)
  final case class KeyNotFound(e: AppException) extends AppIOError(e)

  final case class JavaIO(e: java.io.IOException)     extends IOError(e)
  final case class JavaSecurity(e: SecurityException) extends IOError(e)
  final case class RocksDB(e: RocksDBException)       extends IOError(e)
  final case class Other(e: Throwable)                extends IOError(e)

  def keyNotFound[K](key: K, action: String): KeyNotFound = {
    KeyNotFound(new AppException(s"Key $key not found in $action"))
  }
}
