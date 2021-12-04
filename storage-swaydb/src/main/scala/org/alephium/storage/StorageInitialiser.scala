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

package org.alephium.storage

import java.nio.file.Path

import org.alephium.io.{IOResult, IOUtils}
import org.alephium.storage.setting.StorageSetting
import org.alephium.storage.swaydb.SwayDBSource

object StorageInitialiser extends KeyValueStorageInitialiser {

  override def open(
      path: Path,
      setting: StorageSetting,
      columns: Iterable[ColumnFamily]
  ): IOResult[KeyValueSource] =
    IOUtils.tryOpenStorage(SwayDBSource.defaultUnsafe(path))

  override def openUnsafe(
      path: Path,
      setting: StorageSetting,
      columns: Iterable[ColumnFamily]
  ): KeyValueSource =
    SwayDBSource.defaultUnsafe(path)
}
