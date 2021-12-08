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

import scala.collection.mutable

import akka.util.ByteString
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.flow.model.BrokerDiscoveryState
import org.alephium.io._
import org.alephium.io.RocksDBSource.ColumnFamily
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{BrokerInfo, PeerId}
import org.alephium.serde.{deserialize, SerdeResult}
import org.alephium.util.AVector

trait BrokerStorage extends KeyValueStorage[PeerId, BrokerDiscoveryState] {
  def addBroker(brokerInfo: BrokerInfo): IOResult[Unit]
  def activeBrokers(): IOResult[AVector[BrokerInfo]]
}

object BrokerRocksDBStorage extends RocksDBKeyValueCompanion[BrokerRocksDBStorage] {
  val prefix: ByteString = Hash.hash("discoveredBrokers").bytes

  override def apply(
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  ): BrokerRocksDBStorage = {
    new BrokerRocksDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class BrokerRocksDBStorage(
    val storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[PeerId, BrokerDiscoveryState](
      storage,
      cf,
      writeOptions,
      readOptions
    )
    with BrokerStorage {
  override def storageKey(key: PeerId): ByteString =
    BrokerRocksDBStorage.prefix ++ keySerde.serialize(key)

  override def extractKey(bytes: Array[Byte]): SerdeResult[PeerId] =
    deserialize(ByteString.fromArrayUnsafe(bytes.drop(BrokerRocksDBStorage.prefix.length)))

  override def addBroker(brokerInfo: BrokerInfo): IOResult[Unit] = {
    val state = BrokerDiscoveryState(brokerInfo.address, brokerInfo.brokerNum)
    put(brokerInfo.peerId, state)
  }

  override def activeBrokers(): IOResult[AVector[BrokerInfo]] = {
    val buffer = mutable.ArrayBuffer.empty[BrokerInfo]
    iterateWithPrefix(
      BrokerRocksDBStorage.prefix.toArray,
      (peerId, state) => {
        val brokerInfo = BrokerInfo.unsafe(
          peerId.cliqueId,
          peerId.brokerId,
          state.brokerNum,
          state.address
        )
        buffer += brokerInfo
      }
    ).map(_ => AVector.from(buffer))
  }
}
