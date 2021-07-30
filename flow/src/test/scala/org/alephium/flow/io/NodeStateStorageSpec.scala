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

import org.alephium.io.{IOError, RocksDBSource}
import org.alephium.protocol.Generators
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model.Version
import org.alephium.util.AlephiumSpec

class NodeStateStorageSpec
    extends AlephiumSpec
    with GroupConfigFixture.Default
    with StorageSpec[NodeStateRockDBStorage] {

  override val dbname: String = "node-state-storage-spec"
  override val builder: RocksDBSource => NodeStateRockDBStorage =
    source => NodeStateRockDBStorage(source, RocksDBSource.ColumnFamily.All)

  it should "check database compatibility" in {
    val initDbVersion = Generators.versionGen.sample.get._2
    storage.setDatabaseVersion(initDbVersion) isE ()
    storage.getDatabaseVersion() isE Some(initDbVersion)

    forAll(Generators.versionGen, Generators.versionGen) {
      case ((_, minimalVersion), (_, nodeVersion)) if minimalVersion <= nodeVersion =>
        val dbVersion = storage.getDatabaseVersion().rightValue.get
        if (dbVersion < minimalVersion || dbVersion > nodeVersion) {
          storage
            .checkDatabaseCompatibility(minimalVersion, nodeVersion)
            .leftValue is a[IOError.Other]
        } else {
          storage.checkDatabaseCompatibility(minimalVersion, nodeVersion) isE ()
          storage.getDatabaseVersion() isE Some(nodeVersion)
        }
      case _ => true
    }
  }

  it should "update database version when init" in {
    storage.getDatabaseVersion() isE None

    val version: Version = Generators.versionGen.sample.get._2
    storage.checkDatabaseCompatibility(version, version) isE ()
    storage.getDatabaseVersion() isE Some(version)
  }
}
