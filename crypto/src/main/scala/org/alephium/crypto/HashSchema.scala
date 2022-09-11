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

package org.alephium.crypto

import java.nio.charset.Charset

import akka.util.ByteString
import org.bouncycastle.crypto.Digest

import org.alephium.serde._

object HashSchema {
  def unsafeBlake2b(bs: ByteString): Blake2b = {
    assume(bs.size == Blake2b.length)
    new Blake2b(bs)
  }

  def unsafeBlake3(bs: ByteString): Blake3 = {
    assume(bs.size == Blake3.length)
    new Blake3(bs)
  }

  def unsafeKeccak256(bs: ByteString): Keccak256 = {
    assume(bs.size == Keccak256.length)
    new Keccak256(bs)
  }

  def unsafeSha256(bs: ByteString): Sha256 = {
    assume(bs.size == Sha256.length)
    new Sha256(bs)
  }

  def unsafeSha3(bs: ByteString): Sha3 = {
    assume(bs.size == Sha256.length)
    new Sha3(bs)
  }

  def unsafeByte32(bs: ByteString): Byte32 = {
    assume(bs.size == Byte32.length)
    new Byte32(bs)
  }
}

trait HashUtils[T] {
  def length: Int

  @inline def random: T = generate
  def generate: T

  def hash(bytes: Seq[Byte]): T
  def hash(string: String): T
}

abstract class HashSchema[T](unsafe: ByteString => T, toBytes: T => ByteString)
    extends RandomBytes.Companion[T](unsafe, toBytes)
    with HashUtils[T] {
  def hash(input: Seq[Byte]): T

  def doubleHash(input: Seq[Byte]): T

  def hash(input: String): T = {
    hash(ByteString.fromString(input))
  }

  def hash(input: String, charset: Charset): T = {
    hash(ByteString.fromString(input, charset))
  }

  def hash[S: Serializer](input: S): T = {
    hash(serialize(input))
  }

  def xor(hash0: T, hash1: T): T = {
    val bytes0 = toBytes(hash0)
    val bytes1 = toBytes(hash1)
    val result = Array.tabulate[Byte](length)(index => (bytes0(index) ^ bytes1(index)).toByte)
    unsafe(ByteString.fromArrayUnsafe(result))
  }

  def addPerByte(hash0: T, hash1: T): T = {
    val bytes0 = toBytes(hash0)
    val bytes1 = toBytes(hash1)
    val result = Array.tabulate[Byte](length)(index => (bytes0(index) + bytes1(index)).toByte)
    unsafe(ByteString.fromArrayUnsafe(result))
  }
}

abstract class BCHashSchema[T](unsafe: ByteString => T, toBytes: T => ByteString)
    extends HashSchema[T](unsafe, toBytes) {
  def provider(): Digest

  def hash(input: Seq[Byte]): T = {
    val hashser = provider() // For Thread-safety
    hashser.update(input.toArray, 0, input.length)
    val res = new Array[Byte](length)
    hashser.doFinal(res, 0)
    unsafe(ByteString.fromArrayUnsafe(res))
  }

  def doubleHash(input: Seq[Byte]): T = {
    val hasher = provider() // For Thread-safety
    hasher.update(input.toArray, 0, input.length)
    val res = new Array[Byte](length)
    hasher.doFinal(res, 0)
    hasher.update(res, 0, length)
    hasher.doFinal(res, 0)
    unsafe(ByteString.fromArrayUnsafe(res))
  }
}
