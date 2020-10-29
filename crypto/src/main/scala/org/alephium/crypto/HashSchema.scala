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

import scala.reflect.runtime.universe.TypeTag

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

  def unsafeByte32(bs: ByteString): Byte32 = {
    assume(bs.size == Byte32.length)
    new Byte32(bs)
  }
}

abstract class HashSchema[T: TypeTag](unsafe: ByteString => T, toBytes: T => ByteString)
    extends RandomBytes.Companion[T](unsafe, toBytes) {
  def provider: Digest

  def hash(input: Seq[Byte]): T = {
    val _provider = provider
    _provider.update(input.toArray, 0, input.length)
    val res = new Array[Byte](length)
    _provider.doFinal(res, 0)
    unsafe(ByteString.fromArrayUnsafe(res))
  }

  def hash(input: String): T = {
    hash(ByteString.fromString(input))
  }

  def hash(input: String, charset: Charset): T = {
    hash(ByteString.fromString(input, charset))
  }

  def hash[S](input: S)(implicit serializer: Serializer[S]): T = {
    hash(serializer.serialize(input))
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

  def random: T = generate
}
