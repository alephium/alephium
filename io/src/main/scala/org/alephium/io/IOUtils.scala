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

import java.io.IOException
import java.nio.file.{Files, Path}

import org.rocksdb.RocksDBException

import org.alephium.io.IOError.KeyNotFound
import org.alephium.serde.SerdeError

object IOUtils {
  def createDirUnsafe(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectory(path)
    }
    ()
  }

  def clearUnsafe(path: Path): Unit = {
    if (Files.exists(path) && Files.isDirectory(path)) {
      Files.list(path).forEach(removeUnsafe)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def removeUnsafe(path: Path): Unit = {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.list(path).forEach(removeUnsafe)
      }
      Files.delete(path)
    }
  }

  @inline
  def tryExecute[T](f: => T): IOResult[T] = {
    try Right(f)
    catch error
  }

  @inline
  def tryExecuteF[T](f: => IOResult[T]): IOResult[T] = {
    try f
    catch error
  }

  @inline
  def error[T]: PartialFunction[Throwable, IOResult[T]] = {
    case e: IOException       => Left(IOError.JavaIO(e))
    case e: SecurityException => Left(IOError.JavaSecurity(e))
    case e: RocksDBException  => Left(IOError.RocksDB(e))
    case e: SerdeError        => Left(IOError.Serde(e))
    case e: KeyNotFound[_]    => Left(e)
  }
}
