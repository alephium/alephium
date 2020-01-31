package org.alephium.flow.io

import java.io.IOException

import org.rocksdb.RocksDBException

import org.alephium.serde.SerdeError

sealed abstract class IOError(reason: Throwable) extends Exception(reason)

object IOError {
  case class JavaIO(e: java.io.IOException)     extends IOError(e)
  case class JavaSecurity(e: SecurityException) extends IOError(e)
  case class RocksDB(e: RocksDBException)       extends IOError(e)
  case class Serde(e: SerdeError)               extends IOError(e)
  case class Other(e: Throwable)                extends IOError(e)

  object RocksDB {
    val keyNotFound = RocksDB(new RocksDBException("key not found"))
  }

  @inline
  def execute[T](f: => T): IOResult[T] = {
    try Right(f)
    catch error
  }

  @inline
  def executeF[T](f: => IOResult[T]): IOResult[T] = {
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
