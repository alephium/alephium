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

import org.alephium.flow.core.BlockChain
import org.alephium.flow.core.BlockChain.{TxIndex, TxIndexes}
import org.alephium.io._
import org.alephium.protocol.Hash
import org.alephium.storage.{ColumnFamily, KeyValueSource, KeyValueStorage}
import org.alephium.util.AVector

object TxStorage {
  def apply(storage: KeyValueSource, cf: ColumnFamily): TxStorage =
    new TxStorage(storage, cf)
}

class TxStorage(val storage: KeyValueSource, cf: ColumnFamily)
    extends KeyValueStorage[Hash, BlockChain.TxIndexes](storage, cf) {

  def add(txId: Hash, txIndex: TxIndex): IOResult[Unit] = {
    getOpt(txId).flatMap {
      case Some(txIndexes) => put(txId, TxIndexes(txIndexes.indexes :+ txIndex))
      case None            => put(txId, TxIndexes(AVector(txIndex)))
    }
  }

  def addUnsafe(txId: Hash, txIndex: TxIndex): Unit = {
    getOptUnsafe(txId) match {
      case Some(txIndexes) => putUnsafe(txId, TxIndexes(txIndexes.indexes :+ txIndex))
      case None            => putUnsafe(txId, TxIndexes(AVector(txIndex)))
    }
  }

  override def delete(key: Hash): IOResult[Unit] = ???

  override def deleteUnsafe(key: Hash): Unit = ???
}
