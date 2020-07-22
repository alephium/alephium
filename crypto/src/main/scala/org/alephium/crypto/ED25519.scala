package org.alephium.crypto

import akka.util.ByteString
import org.bouncycastle.math.ec.rfc8032.{Ed25519 => bcEd25519}

import org.alephium.serde.RandomBytes
import org.alephium.util.{AVector, Random}

class ED25519PrivateKey(val bytes: ByteString) extends PrivateKey

object ED25519PrivateKey
    extends RandomBytes.Companion[ED25519PrivateKey](bs => {
      assert(bs.size == bcEd25519.SECRET_KEY_SIZE)
      new ED25519PrivateKey(bs)
    }, _.bytes) {
  override def length: Int = bcEd25519.SECRET_KEY_SIZE
}

class ED25519PublicKey(val bytes: ByteString) extends PublicKey {
  def toByte32: Byte32 = Byte32.unsafe(bytes)
}

object ED25519PublicKey
    extends RandomBytes.Companion[ED25519PublicKey](bs => {
      assert(bs.size == bcEd25519.PUBLIC_KEY_SIZE)
      new ED25519PublicKey(bs)
    }, _.bytes) {
  override def length: Int = bcEd25519.PUBLIC_KEY_SIZE
}

class ED25519Signature(val bytes: ByteString) extends Signature

object ED25519Signature
    extends RandomBytes.Companion[ED25519Signature](bs => {
      assert(bs.size == bcEd25519.SIGNATURE_SIZE)
      new ED25519Signature(bs)
    }, _.bytes) {
  override def length: Int = bcEd25519.SIGNATURE_SIZE
}

object ED25519 extends SignatureSchema[ED25519PrivateKey, ED25519PublicKey, ED25519Signature] {
  override def generatePriPub(): (ED25519PrivateKey, ED25519PublicKey) = {
    val privateKey = Array.ofDim[Byte](ED25519PrivateKey.length)
    val publicKey  = Array.ofDim[Byte](ED25519PublicKey.length)
    bcEd25519.generatePrivateKey(Random.source, privateKey)
    bcEd25519.generatePublicKey(privateKey, 0, publicKey, 0)
    (ED25519PrivateKey.unsafe(ByteString.fromArrayUnsafe(privateKey)),
     ED25519PublicKey.unsafe(ByteString.fromArrayUnsafe(publicKey)))
  }

  override def sign(message: ByteString, privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  override def sign(message: AVector[Byte], privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  private def sign(message: Array[Byte], privateKey: Array[Byte]): ED25519Signature = {
    val signature = Array.ofDim[Byte](ED25519Signature.length)
    bcEd25519.sign(privateKey, 0, message, 0, message.length, signature, 0)
    ED25519Signature.unsafe(ByteString.fromArrayUnsafe(signature))
  }

  override def verify(message: ByteString,
                      signature: ED25519Signature,
                      publicKey: ED25519PublicKey): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  override def verify(message: AVector[Byte],
                      signature: ED25519Signature,
                      publicKey: ED25519PublicKey): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  private def verify(message: Array[Byte],
                     signature: Array[Byte],
                     publicKey: Array[Byte]): Boolean = {
    bcEd25519.verify(signature, 0, publicKey, 0, message, 0, message.length)
  }
}
