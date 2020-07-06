package org.alephium.crypto

import akka.util.ByteString

import org.alephium.serde.RandomBytes

class Byte32(val bytes: ByteString) extends RandomBytes

object Byte32 extends RandomBytes.Companion[Byte32](HashSchema.unsafeByte32, _.bytes) {
  override def length: Int = 32
}
