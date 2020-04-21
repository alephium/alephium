package org.alephium.flow.io

trait KeyValueSource {
  def close(): IOResult[Unit]

  def closeUnsafe(): Unit

  def dESTROY(): IOResult[Unit]

  def dESTROYUnsafe(): Unit
}
