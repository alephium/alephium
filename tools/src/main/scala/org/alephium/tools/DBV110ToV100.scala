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

package org.alephium.tools

import java.nio.charset.StandardCharsets

import akka.util.ByteString

import org.alephium.flow.io.{DatabaseVersion, Storages}
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.Hash
import org.alephium.serde.serialize
import org.alephium.util.{Bytes, Files}

object DBV110ToV100 extends App {
  private val rootPath      = Files.homeDir.resolve(".alephium/mainnet")
  private val brokerCfBytes = ColumnFamily.Broker.name.getBytes(StandardCharsets.UTF_8)
  private val allCfBytes    = ColumnFamily.All.name.getBytes(StandardCharsets.UTF_8)

  private val dbVersionKey =
    (Hash.hash("databaseVersion").bytes ++ ByteString(Storages.dbVersionPostfix)).toArray
  private val dbVersion100 = serialize(
    DatabaseVersion(Bytes.toIntUnsafe(ByteString(0, 1, 0, 0)))
  ).toArray
  private val dbVersion110 = serialize(
    DatabaseVersion(Bytes.toIntUnsafe(ByteString(0, 1, 1, 0)))
  ).toArray
  private val rocksDBSource = Storages.createRocksDBUnsafe(rootPath, "db")

  rocksDBSource.cfHandles.find(_.getName sameElements allCfBytes).foreach { allCfHandler =>
    val currentDBVersion = rocksDBSource.db.get(allCfHandler, dbVersionKey)
    if (currentDBVersion sameElements dbVersion110) {
      rocksDBSource.cfHandles.find(_.getName sameElements brokerCfBytes).foreach {
        brokerCfHandler =>
          rocksDBSource.db.dropColumnFamily(brokerCfHandler)
      }

      rocksDBSource.db.put(allCfHandler, dbVersionKey, dbVersion100)
    }
  }
}
