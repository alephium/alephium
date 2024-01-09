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

import akka.util.ByteString

import org.alephium.serde.Serde

trait MockFactory {
  def unimplementedStorage[K, V]: KeyValueStorage[K, V] =
    new KeyValueStorage[K, V] {
      implicit override def keySerde: Serde[K]   = ???
      implicit override def valueSerde: Serde[V] = ???

      override def getRawUnsafe(key: ByteString): ByteString                     = ???
      override def getOptRawUnsafe(key: ByteString): Option[ByteString]          = ???
      override def multiGetRawUnsafe(keys: Seq[ByteString]): Seq[ByteString]     = ???
      override def putRawUnsafe(key: ByteString, value: ByteString): Unit        = ???
      def putBatchRawUnsafe(f: ((ByteString, ByteString) => Unit) => Unit): Unit = ???
      def deleteBatchRawUnsafe(keys: Seq[ByteString]): Unit                      = ???
      override def existsRawUnsafe(key: ByteString): Boolean                     = ???
      override def deleteRawUnsafe(key: ByteString): Unit                        = ???
    }
}
