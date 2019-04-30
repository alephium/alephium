package org.alephium.flow.io

import org.alephium.serde.SerdeError
import org.rocksdb.RocksDBException

sealed abstract class IOError(reason: Throwable) extends Exception(reason)

object IOError {

  case class JavaIO(e: java.io.IOException) extends IOError(e)

  case class RocksDB(e: RocksDBException) extends IOError(e)

  object RocksDB {
    val keyNotFound = RocksDB(new RocksDBException("key not found"))
  }

  case class Serde(e: SerdeError) extends IOError(e)

  case class Other(e: Throwable) extends IOError(e)

  def apply(t: Throwable): IOError = t match {
    case e: java.io.IOException => JavaIO(e)
    case e: RocksDBException    => RocksDB(e)
    case e: SerdeError          => Serde(e)
    case _                      => Other(t)
  }
}
