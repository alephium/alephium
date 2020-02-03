package org.alephium.flow.io

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
}
