package org.alephium.flow.io

import org.rocksdb.RocksDBException

import org.alephium.serde.SerdeError

sealed abstract class IOError(reason: Throwable) extends Exception(reason)

object IOError {
  final case class JavaIO(e: java.io.IOException)     extends IOError(e)
  final case class JavaSecurity(e: SecurityException) extends IOError(e)
  final case class RocksDB(e: RocksDBException)       extends IOError(e)
  final case class Serde(e: SerdeError)               extends IOError(e)
  final case class Other(e: Throwable)                extends IOError(e)

  object RocksDB {
    val keyNotFound: IOError.RocksDB = RocksDB(new RocksDBException("key not found"))
  }
}
