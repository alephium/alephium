// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.serde

import scala.util.Random

import akka.util.ByteString

import org.alephium.util.{Bytes, Hex, SecureAndSlowRandom}

trait RandomBytes extends Any {
  def bytes: ByteString

  def last: Byte = bytes(bytes.size - 1)

  def beforeLast: Byte = {
    val size = bytes.size
    assume(size >= 2)
    bytes(size - 2)
  }

  override def hashCode(): Int = {
    val size = bytes.size

    assume(size >= 4)

    (bytes(size - 4) & 0xff) << 24 |
      (bytes(size - 3) & 0xff) << 16 |
      (bytes(size - 2) & 0xff) << 8 |
      (bytes(size - 1) & 0xff)
  }

  override def equals(obj: Any): Boolean =
    obj match {
      case that: RandomBytes => bytes == that.bytes
      case _                 => false
    }

  override def toString: String = {
    val hex  = Hex.toHexString(bytes)
    val name = this.getClass.getSimpleName
    s"""$name(hex"$hex")"""
  }

  def toHexString: String = Hex.toHexString(bytes)

  def shortHex: String = toHexString.takeRight(8)

  // Only use this when length % 4 == 0
  def toRandomIntUnsafe: Int =
    bytes.sliding(4, 4).foldLeft(0) { case (acc, subBytes) =>
      acc + Bytes.toIntUnsafe(subBytes)
    }
}

object RandomBytes {
  abstract class Companion[T](val unsafe: ByteString => T, val toBytes: T => ByteString) {
    lazy val zero: T = unsafe(ByteString.fromArrayUnsafe(Array.fill[Byte](length)(0)))

    lazy val allOne: T = unsafe(ByteString.fromArrayUnsafe(Array.fill[Byte](length)(0xff.toByte)))

    def length: Int

    def from(bytes: IndexedSeq[Byte]): Option[T] = {
      if (bytes.length == length) {
        Some(unsafe(ByteString.fromArrayUnsafe(bytes.toArray)))
      } else {
        None
      }
    }

    def from(bytes: ByteString): Option[T] = {
      if (bytes.nonEmpty && bytes.length == length) {
        Some(unsafe(bytes))
      } else {
        None
      }
    }

    def generate: T = {
      val xs = Array.ofDim[Byte](length)
      Random.nextBytes(xs)
      unsafe(ByteString.fromArrayUnsafe(xs))
    }

    def secureGenerate: T = {
      val xs = Array.ofDim[Byte](length)
      SecureAndSlowRandom.source.nextBytes(xs)
      unsafe(ByteString.fromArrayUnsafe(xs))
    }

    implicit val serde: Serde[T] = Serde.bytesSerde(length).xmap(unsafe, toBytes)
  }
}
