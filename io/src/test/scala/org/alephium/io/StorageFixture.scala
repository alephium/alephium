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

import java.nio.file.{Files => JFiles}

import org.alephium.crypto.Keccak256
import org.alephium.serde.Serde
import org.alephium.util.{AlephiumFixture, AlephiumSpec, Env, Files}

trait StorageFixture extends AlephiumFixture {

  def newDBStorage(): RocksDBSource = {
    val rootPath = Files.testRootPath(Env.currentEnv)
    if (!JFiles.exists(rootPath)) {
      rootPath.toFile.mkdir()
    }
    val dbname  = s"test-db-${Keccak256.generate.toHexString}"
    val dbPath  = rootPath.resolve(dbname)
    val storage = RocksDBSource.openUnsafe(dbPath)
    AlephiumSpec.addCleanTask(() => storage.dESTROYUnsafe())
    storage
  }

  def newDB[K: Serde, V: Serde](
      storage: RocksDBSource,
      cf: RocksDBSource.ColumnFamily
  ): KeyValueStorage[K, V] = {
    RocksDBKeyValueStorage[K, V](storage, cf)
  }
}
