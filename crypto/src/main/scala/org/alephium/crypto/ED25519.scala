package org.alephium.crypto

import akka.util.ByteString
import org.whispersystems.curve25519.Curve25519

import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

class ED25519PrivateKey(val bytes: ByteString) extends PrivateKey

object ED25519PrivateKey
    extends RandomBytes.Companion[ED25519PrivateKey](bs => {
      assert(bs.size == ed25519KeyLength)
      new ED25519PrivateKey(bs)
    }, _.bytes) {
  override def length: Int = ed25519KeyLength
}

class ED25519PublicKey(val bytes: ByteString) extends PublicKey

object ED25519PublicKey
    extends RandomBytes.Companion[ED25519PublicKey](bs => {
      assert(bs.size == ed25519KeyLength)
      new ED25519PublicKey(bs)
    }, _.bytes) {
  override def length: Int = ed25519KeyLength
}

class ED25519Signature(val bytes: ByteString) extends Signature

object ED25519Signature
    extends RandomBytes.Companion[ED25519Signature](bs => {
      assert(bs.size == ed25519SigLength)
      new ED25519Signature(bs)
    }, _.bytes) {
  override def length: Int = ed25519SigLength

  def isCanonical(bytes: ByteString): Boolean = {
    assert(bytes.length == length)
    val sBytes = bytes.takeRight(32).toArray.reverse
    sBytes(0) = (sBytes(0) & 0x7F).toByte
    val s = BigInt(sBytes)
    s >= 0 && s < ED25519.generator
  }
}

object ED25519 extends SignatureSchema[ED25519PrivateKey, ED25519PublicKey, ED25519Signature] {

  val curve25519: Curve25519 = Curve25519.getInstance(Curve25519.BEST)

  val generator: BigInt = BigInt(
    "7237005577332262213973186563042994240857116359379907606001950938285454250989")

  override def generatePriPub(): (ED25519PrivateKey, ED25519PublicKey) = {
    val keyPair    = curve25519.generateKeyPair()
    val privateKey = ED25519PrivateKey.unsafeFrom(ByteString.fromArrayUnsafe(keyPair.getPrivateKey))
    val publicKey  = ED25519PublicKey.unsafeFrom(ByteString.fromArrayUnsafe(keyPair.getPublicKey))
    (privateKey, publicKey)
  }

  override def sign(message: ByteString, privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  override def sign(message: AVector[Byte], privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  private def sign(message: Array[Byte], privateKey: Array[Byte]): ED25519Signature = {
    val signature = curve25519.calculateSignature(privateKey, message)
    ED25519Signature.unsafeFrom(ByteString.fromArrayUnsafe(signature))
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
    curve25519.verifySignature(publicKey, message, signature)
  }
}
