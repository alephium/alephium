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

import scala.jdk.CollectionConverters.CollectionHasAsScala

import akka.util.ByteString
import org.rocksdb._

import org.alephium.flow.io.{DatabaseVersion, Storages}
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.Hash
import org.alephium.serde.serialize
import org.alephium.util.{Bytes, Files}

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
object DBV110ToV100 extends App {
  private val rootPath      = Files.homeDir.resolve(".alephium/mainnet")
  private val dbPath        = rootPath.resolve("db").toString
  private val brokerCfBytes = ColumnFamily.Broker.name.getBytes(StandardCharsets.UTF_8)
  private val allCfBytes    = ColumnFamily.All.name.getBytes(StandardCharsets.UTF_8)
  private val cfsName       = RocksDB.listColumnFamilies(new Options(), dbPath).asScala;
  if (cfsName.exists(_.sameElements(brokerCfBytes))) {
    val rocksDBSource = Storages.createRocksDBUnsafe(rootPath, "db")

    rocksDBSource.cfHandles.find(_.getName sameElements brokerCfBytes).foreach { handle =>
      rocksDBSource.db.dropColumnFamily(handle)
    }

    val dbVersionKey = Hash.hash("databaseVersion").bytes ++ ByteString(Storages.dbVersionPostfix)
    val dbVersion100 = DatabaseVersion(Bytes.toIntUnsafe(ByteString(0, 1, 0, 0)))
    rocksDBSource.cfHandles.find(_.getName sameElements allCfBytes).foreach { handle =>
      rocksDBSource.db.put(handle, dbVersionKey.toArray, serialize(dbVersion100).toArray)
    }
  }
}
