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

/** Unsafe trait for storage-engine access.
  *
  * Being unsafe this trait is implemented by storage projects only.
  *
  * Use [[KeyValueStorage]] instead for safer APIs per [[ColumnFamily]].
  */
trait KeyValueSource extends KeyValueSourceDestroyable {

  /** Type used by storage-engine to identify a column.
    *  - In RocksDB this is `ColumnFamilyHandle`
    *  - In SwayDB this is `MultiMap`
    */
  type COLUMN

  private[storage] def getColumnUnsafe(column: ColumnFamily): COLUMN

  private[storage] def getUnsafe(column: COLUMN, key: Array[Byte]): Option[Array[Byte]]

  private[storage] def existsUnsafe(column: COLUMN, key: Array[Byte]): Boolean

  private[storage] def putUnsafe(column: COLUMN, key: Array[Byte], value: Array[Byte]): Unit

  private[storage] def deleteUnsafe(column: COLUMN, key: Array[Byte]): Unit

  private[storage] def deleteRangeUnsafe(
      column: COLUMN,
      fromKey: Array[Byte],
      toKey: Array[Byte]
  ): Unit

  private[storage] def iterateUnsafe(column: COLUMN, f: (Array[Byte], Array[Byte]) => Unit): Unit

}
