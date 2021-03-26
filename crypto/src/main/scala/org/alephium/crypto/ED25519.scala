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
import org.bouncycastle.math.ec.rfc8032.{Ed25519 => bcEd25519}

import org.alephium.serde.RandomBytes

class ED25519PrivateKey(val bytes: ByteString) extends PrivateKey {
  def publicKey: ED25519PublicKey = {
    val privateBytes = bytes.toArray
    val publicBytes  = Array.ofDim[Byte](bcEd25519.PUBLIC_KEY_SIZE)
    bcEd25519.generatePublicKey(privateBytes, 0, publicBytes, 0)
    ED25519PublicKey.unsafe(ByteString.fromArrayUnsafe(publicBytes))
  }
}

object ED25519PrivateKey
    extends RandomBytes.Companion[ED25519PrivateKey](
      bs => {
        assume(bs.size == bcEd25519.SECRET_KEY_SIZE)
        new ED25519PrivateKey(bs)
      },
      _.bytes
    ) {
  override def length: Int = bcEd25519.SECRET_KEY_SIZE
}

class ED25519PublicKey(val bytes: ByteString) extends PublicKey {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object ED25519PublicKey
    extends RandomBytes.Companion[ED25519PublicKey](
      bs => {
        assume(bs.size == bcEd25519.PUBLIC_KEY_SIZE)
        new ED25519PublicKey(bs)
      },
      _.bytes
    ) {
  override def length: Int = bcEd25519.PUBLIC_KEY_SIZE
}

class ED25519Signature(val bytes: ByteString) extends Signature

object ED25519Signature
    extends RandomBytes.Companion[ED25519Signature](
      bs => {
        assume(bs.size == bcEd25519.SIGNATURE_SIZE)
        new ED25519Signature(bs)
      },
      _.bytes
    ) {
  override def length: Int = bcEd25519.SIGNATURE_SIZE
}

object ED25519 extends SignatureSchema[ED25519PrivateKey, ED25519PublicKey, ED25519Signature] {
  override def generatePriPub(): (ED25519PrivateKey, ED25519PublicKey) = {
    val privateKey = ED25519PrivateKey.generate
    (privateKey, privateKey.publicKey)
  }

  override def secureGeneratePriPub(): (ED25519PrivateKey, ED25519PublicKey) = {
    val privateKey = ED25519PrivateKey.secureGenerate
    (privateKey, privateKey.publicKey)
  }

  protected def sign(message: Array[Byte], privateKey: Array[Byte]): ED25519Signature = {
    val signature = Array.ofDim[Byte](ED25519Signature.length)
    bcEd25519.sign(privateKey, 0, message, 0, message.length, signature, 0)
    ED25519Signature.unsafe(ByteString.fromArrayUnsafe(signature))
  }

  protected def verify(
      message: Array[Byte],
      signature: Array[Byte],
      publicKey: Array[Byte]
  ): Boolean = {
    bcEd25519.verify(signature, 0, publicKey, 0, message, 0, message.length)
  }
}
