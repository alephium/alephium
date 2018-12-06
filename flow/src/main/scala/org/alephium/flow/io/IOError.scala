package org.alephium.flow.io

import org.alephium.serde.SerdeError
import org.rocksdb.RocksDBException

sealed trait IOError extends Exception {
  def e: Exception
  override def toString: String = e.toString
}
object IOError {
  def from(expt: Exception): IOError = expt match {
    case e: java.io.IOException => IOExpt(e)
    case e: RocksDBException    => RocksDBExpt(e)
    case e: SerdeError          => SerdeExpt(e)
    case e                      => OtherExpt(e)
  }
}

case class IOExpt(e: java.io.IOException) extends IOError

case class RocksDBExpt(e: RocksDBException) extends IOError
object RocksDBExpt {
  val keyNotFound = RocksDBExpt(new RocksDBException("key not found"))
}

case class SerdeExpt(e: SerdeError) extends IOError

case class OtherExpt(e: Exception) extends IOError
