package org.alephium.io

import org.rocksdb.RocksDBException

import org.alephium.serde.SerdeError

sealed abstract class IOError(val reason: Throwable) extends Exception(reason)

object IOError {
  final case class JavaIO(e: java.io.IOException)     extends IOError(e)
  final case class JavaSecurity(e: SecurityException) extends IOError(e)
  final case class RocksDB(e: RocksDBException)       extends IOError(e)
  final case class Serde(e: SerdeError)               extends IOError(e)
  final case class KeyNotFound[K](key: K)             extends IOError(new Exception(s"Key $key not found"))
  final case class Other(e: Throwable)                extends IOError(e)
}
