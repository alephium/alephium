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

import akka.util.ByteString
import org.bouncycastle.crypto.Digest

import org.alephium.blake3jni.{Blake3Jni, Blake3LibLoader}
import org.alephium.serde.RandomBytes

class Blake3(val bytes: ByteString) extends RandomBytes {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object Blake3 extends HashSchema[Blake3](HashSchema.unsafeBlake3, _.bytes) {

  Blake3LibLoader.loadLibrary();

  override def length: Int = 32

  // TODO: optimize with queue of providers
  override def provider: Digest = new Blake3Digest(length * 8)

  //TODO Improve safety, code might throw errors, e.g. call `update` while hasher has been deleted
  private class Blake3Digest(bitLength: Int) extends Digest {
    private var hasher = Blake3Jni.allocate_hasher()
    Blake3Jni.blake3_hasher_init(hasher)

    override def getAlgorithmName(): String = "BLAKE3"

    override def getDigestSize(): Int = bitLength / 8

    override def update(in: Byte): Unit =
      Blake3Jni.blake3_hasher_update(hasher, Array(in), 1)

    override def update(in: Array[Byte], inOff: Int, len: Int): Unit =
      Blake3Jni.blake3_hasher_update(hasher, in.drop(inOff), len)

    override def doFinal(out: Array[Byte], outOff: Int): Int = {
      val size = getDigestSize()
      Blake3Jni.blake3_hasher_finalize(hasher, out, size)

      Blake3Jni.delete_hasher(hasher)
      size
    }

    override def reset(): Unit = {
      Blake3Jni.delete_hasher(hasher)
      hasher = Blake3Jni.allocate_hasher()
      Blake3Jni.blake3_hasher_init(hasher)
    }
  }
}
