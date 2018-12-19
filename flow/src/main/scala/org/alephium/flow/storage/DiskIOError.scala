package org.alephium.flow.storage

import org.alephium.serde.SerdeError

sealed trait DiskIOError
case class IOError(msg: String)     extends DiskIOError
case class DecodeError(msg: String) extends DiskIOError

object DiskIOError {
  def from(e: Throwable): DiskIOError = {
    IOError(e.toString)
  }

  def from(e: SerdeError): DiskIOError = {
    DecodeError(e.toString)
  }
}
