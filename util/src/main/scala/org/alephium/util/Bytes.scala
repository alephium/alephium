package org.alephium.util

import scala.math.Ordering.{Boolean => BooleanOrdering, Byte => ByteOrdering}

import akka.util.ByteString

object Bytes {
  def toPosInt(byte: Byte): Int = {
    byte & 0xFF
  }

  def from(value: Int): ByteString = {
    ByteString((value >> 24).toByte, (value >> 16).toByte, (value >> 8).toByte, value.toByte)
  }

  def toIntUnsafe(bytes: ByteString): Int = {
    assume(bytes.length == 4)
    bytes(0) << 24 | (bytes(1) & 0xFF) << 16 | (bytes(2) & 0xFF) << 8 | (bytes(3) & 0xFF)
  }

  def toBytes(value: Long): ByteString = {
    ByteString((value >> 56).toByte,
               (value >> 48).toByte,
               (value >> 40).toByte,
               (value >> 32).toByte,
               (value >> 24).toByte,
               (value >> 16).toByte,
               (value >> 8).toByte,
               value.toByte)
  }

  def toLongUnsafe(bytes: ByteString): Long = {
    assume(bytes.length == 8)
    (bytes(0) & 0xFFL) << 56 |
      (bytes(1) & 0xFFL) << 48 |
      (bytes(2) & 0xFFL) << 40 |
      (bytes(3) & 0xFFL) << 32 |
      (bytes(4) & 0xFFL) << 24 |
      (bytes(5) & 0xFFL) << 16 |
      (bytes(6) & 0xFFL) << 8 |
      (bytes(7) & 0xFFL)
  }

  def xorByte(value: Int): Byte = {
    val byte0 = (value >> 24).toByte
    val byte1 = (value >> 16).toByte
    val byte2 = (value >> 8).toByte
    val byte3 = value.toByte
    (byte0 ^ byte1 ^ byte2 ^ byte3).toByte
  }

  implicit val byteStringOrdering: Ordering[ByteString] = new Ordering[ByteString] {
    override def compare(x: ByteString, y: ByteString): Int = {
      val xe = x.iterator
      val ye = y.iterator

      while (xe.hasNext && ye.hasNext) {
        val res = ByteOrdering.compare(xe.next(), ye.next())
        if (res != 0) return res
      }

      BooleanOrdering.compare(xe.hasNext, ye.hasNext)
    }
  }
}
