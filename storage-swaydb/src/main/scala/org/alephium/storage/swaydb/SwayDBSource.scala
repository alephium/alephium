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

package org.alephium.storage.swaydb

import java.nio.file.Path

import swaydb._
import swaydb.serializers.Default.{ByteArraySerializer, StringSerializer}

import org.alephium.storage.{ColumnFamily, KeyValueSource}
import org.alephium.storage.swaydb.SwayDBSource.MultiMap

object SwayDBSource {

  type MultiMap = swaydb.MultiMap[String, Array[Byte], Array[Byte], Nothing, Glass]

  def defaultUnsafe(path: Path): KeyValueSource = {
    implicit val bag: Bag.Sync[Glass] = GlassBag.bag
    val root                          = persistent.MultiMap[String, Array[Byte], Array[Byte], Nothing, Glass](path)
    new SwayDBSource(root)
  }
}

protected class SwayDBSource(root: MultiMap) extends KeyValueSource {

  override type COLUMN = MultiMap

  override def getColumnUnsafe(column: ColumnFamily): MultiMap =
    root.child(column.name)

  override def getUnsafe(column: MultiMap, key: Array[Byte]): Option[Array[Byte]] =
    column.get(key)

  override def existsUnsafe(column: MultiMap, key: Array[Byte]): Boolean =
    column.contains(key)

  override def putUnsafe(column: MultiMap, key: Array[Byte], value: Array[Byte]): Unit = {
    column.put(key, value)
    ()
  }

  override def deleteUnsafe(column: MultiMap, key: Array[Byte]): Unit = {
    column.remove(key)
    ()
  }

  override def deleteRangeUnsafe(
      column: MultiMap,
      fromKey: Array[Byte],
      toKey: Array[Byte]
  ): Unit = {
    column.remove(fromKey, toKey)
    ()
  }

  override def iterateUnsafe(column: MultiMap, f: (Array[Byte], Array[Byte]) => Unit): Unit =
    column.foreach { case (key, value) => f(key, value) }

  override def closeUnsafe(): Unit =
    root.close()

  override def dESTROYUnsafe(): Unit =
    root.delete()

}
