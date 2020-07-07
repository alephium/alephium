package org.alephium.flow.io

import org.alephium.io.IOResult

trait KeyValueSource {
  def close(): IOResult[Unit]

  def closeUnsafe(): Unit

  def dESTROY(): IOResult[Unit]

  def dESTROYUnsafe(): Unit
}
