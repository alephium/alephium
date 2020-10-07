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

import org.scalatest.Assertion

import org.alephium.crypto.Keccak256
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.serde.{Serde, Serializer}
import org.alephium.util.{AlephiumSpec, Files}

trait StorageFixture extends AlephiumSpec {
  private lazy val tmpdir = Files.tmpDir
  private lazy val dbname = s"test-db-${Keccak256.generate.toHexString}"
  private lazy val dbPath = tmpdir.resolve(dbname)

  private lazy val storage = RocksDBSource.openUnsafe(dbPath, RocksDBSource.Compaction.HDD)

  def newDB[K: Serializer, V: Serde]: KeyValueStorage[K, V] =
    RocksDBKeyValueStorage[K, V](storage, ColumnFamily.All)

  protected def postTest(): Assertion = {
    storage.dESTROY().isRight is true
  }
}
