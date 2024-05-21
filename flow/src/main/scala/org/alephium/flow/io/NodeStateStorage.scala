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

import akka.util.ByteString
import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteOptions}

import org.alephium.flow.core.BlockHashChain
import org.alephium.flow.model.BootstrapInfo
import org.alephium.io._
import org.alephium.io.RocksDBSource.{ColumnFamily, ProdSettings}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.ChainIndex
import org.alephium.serde._
import org.alephium.util.AVector

trait NodeStateStorage extends RawKeyValueStorage {

  def config: GroupConfig

  private val isInitializedKey =
    Hash.hash("isInitialized").bytes ++ ByteString(Storages.isInitializedPostfix)

  def isInitialized(): IOResult[Boolean] =
    IOUtils.tryExecute {
      existsRawUnsafe(isInitializedKey)
    }

  def setInitialized(): IOResult[Unit] =
    IOUtils.tryExecute {
      putRawUnsafe(isInitializedKey, ByteString(1))
    }

  private def getByKeyOpt[V: Deserializer](key: ByteString): IOResult[Option[V]] =
    IOUtils.tryExecute {
      getOptRawUnsafe(key).map(deserialize[V](_) match {
        case Left(e)  => throw e
        case Right(v) => v
      })
    }

  private val bootstrapInfoKey =
    Hash.hash("bootstrapInfo").bytes ++ ByteString(Storages.bootstrapInfoPostFix)

  def getBootstrapInfo(): IOResult[Option[BootstrapInfo]] = getByKeyOpt(bootstrapInfoKey)

  def setBootstrapInfo(info: BootstrapInfo): IOResult[Unit] = {
    IOUtils.tryExecute(putRawUnsafe(bootstrapInfoKey, serialize(info)))
  }

  private val dbVersionKey =
    Hash.hash("databaseVersion").bytes ++ ByteString(Storages.dbVersionPostfix)

  def setDatabaseVersion(version: DatabaseVersion): IOResult[Unit] =
    IOUtils.tryExecute {
      putRawUnsafe(dbVersionKey, serialize(version))
    }

  def getDatabaseVersion(): IOResult[Option[DatabaseVersion]] = getByKeyOpt(dbVersionKey)

  def checkDatabaseCompatibility(): IOResult[Unit] = {
    getDatabaseVersion().flatMap {
      case Some(dbVersion) =>
        if (dbVersion > DatabaseVersion.currentDBVersion) {
          Left(
            IOError.Other(
              new RuntimeException(
                s"Database version is not compatible: got $dbVersion, expect ${DatabaseVersion.currentDBVersion}"
              )
            )
          )
        } else if (dbVersion < DatabaseVersion.currentDBVersion) {
          setDatabaseVersion(DatabaseVersion.currentDBVersion)
        } else {
          Right(())
        }
      case None =>
        setDatabaseVersion(DatabaseVersion.currentDBVersion)
    }
  }

  private val chainStateKeys = AVector.tabulate(config.groups, config.groups) { (from, to) =>
    ByteString(from.toByte, to.toByte, Storages.chainStatePostfix)
  }

  def chainStateStorage(chainIndex: ChainIndex): ChainStateStorage =
    new ChainStateStorage {
      private val chainStateKey = chainStateKeys(chainIndex.from.value)(chainIndex.to.value)

      override def updateState(state: BlockHashChain.State): IOResult[Unit] =
        IOUtils.tryExecute {
          putRawUnsafe(chainStateKey, serialize(state))
        }

      override def loadState(): IOResult[BlockHashChain.State] =
        IOUtils.tryExecute {
          deserialize[BlockHashChain.State](getRawUnsafe(chainStateKey)) match {
            case Left(e)  => throw e
            case Right(v) => v
          }
        }

      override def clearState(): IOResult[Unit] =
        IOUtils.tryExecute {
          deleteRawUnsafe(chainStateKey)
        }
    }

  def heightIndexStorage(chainIndex: ChainIndex): HeightIndexStorage
}

object NodeStateRockDBStorage {
  def apply(storage: RocksDBSource, cf: ColumnFamily)(implicit
      config: GroupConfig
  ): NodeStateRockDBStorage =
    apply(storage, cf, ProdSettings.writeOptions, ProdSettings.readOptions)

  def apply(storage: RocksDBSource, cf: ColumnFamily, writeOptions: WriteOptions)(implicit
      config: GroupConfig
  ): NodeStateRockDBStorage =
    apply(storage, cf, writeOptions, ProdSettings.readOptions)

  def apply(
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  )(implicit config: GroupConfig): NodeStateRockDBStorage = {
    new NodeStateRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class NodeStateRockDBStorage(
    val storage: RocksDBSource,
    val cf: ColumnFamily,
    val writeOptions: WriteOptions,
    val readOptions: ReadOptions
)(implicit val config: GroupConfig)
    extends RocksDBColumn
    with NodeStateStorage {
  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)

  def heightIndexStorage(chainIndex: ChainIndex): HeightIndexStorage =
    new HeightIndexStorage(chainIndex, storage, cf, writeOptions, readOptions)
}
