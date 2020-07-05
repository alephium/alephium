package org.alephium.protocol.util

import java.io.IOException
import java.nio.file.{Files, Path}

import org.rocksdb.RocksDBException

import org.alephium.protocol.io.IOResult
import org.alephium.serde.SerdeError

object IOUtils {
  import org.alephium.protocol.io.IOError._

  def createDirUnsafe(path: Path): Unit = {
    if (!Files.exists(path)) {
      Files.createDirectory(path)
    }
    ()
  }

  def clearUnsafe(path: Path): Unit = {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.list(path).forEach(removeUnsafe)
      }
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
    case e: IOException       => Left(JavaIO(e))
    case e: SecurityException => Left(JavaSecurity(e))
    case e: RocksDBException  => Left(RocksDB(e))
    case e: SerdeError        => Left(Serde(e))
  }
}
