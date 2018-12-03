package org.alephium.crypto

import akka.util.ByteString
import org.alephium.util.FixedSizeBytes
import org.whispersystems.curve25519.Curve25519

case class ED25519PrivateKey(bytes: Seq[Byte]) extends PrivateKey

object ED25519PrivateKey extends FixedSizeBytes[ED25519PrivateKey] {
  override val size: Int = 32

  private def apply(bytes: Seq[Byte]): ED25519PrivateKey = {
    require(bytes.length == size)
    new ED25519PrivateKey(bytes)
  }

  override def unsafeFrom(data: Seq[Byte]): ED25519PrivateKey = apply(data)
}

case class ED25519PublicKey(bytes: Seq[Byte]) extends PublicKey

object ED25519PublicKey extends FixedSizeBytes[ED25519PublicKey] {
  override val size: Int = 32

  private def apply(bytes: Seq[Byte]): ED25519PublicKey = {
    require(bytes.length == size)
    new ED25519PublicKey(bytes)
  }

  override def unsafeFrom(data: Seq[Byte]): ED25519PublicKey = apply(data)
}

case class ED25519Signature(bytes: Seq[Byte]) extends Signature

object ED25519Signature extends FixedSizeBytes[ED25519Signature] {
  override val size: Int = 64

  def isCanonical(bytes: Seq[Byte]): Boolean = {
    require(bytes.length == size)
    val sBytes = bytes.takeRight(32).toArray.reverse
    sBytes(0) = (sBytes(0) & 0x7F).toByte
    val s = BigInt(sBytes)
    s >= BigInt(0) && s < ED25519.generator
  }

  private def apply(bytes: Seq[Byte]): ED25519Signature = {
    require(bytes.length == size && isCanonical(bytes))
    new ED25519Signature(bytes)
  }

  override def unsafeFrom(data: Seq[Byte]): ED25519Signature = apply(data)
}

object ED25519 extends SignatureSchema[ED25519PrivateKey, ED25519PublicKey, ED25519Signature] {

  val curve25519: Curve25519 = Curve25519.getInstance(Curve25519.BEST)

  val generator: BigInt = BigInt(
    "7237005577332262213973186563042994240857116359379907606001950938285454250989")

  override def generateKeyPair(): (ED25519PrivateKey, ED25519PublicKey) = {
    val keyPair    = curve25519.generateKeyPair()
    val privateKey = ED25519PrivateKey.unsafeFrom(keyPair.getPrivateKey)
    val publicKey  = ED25519PublicKey.unsafeFrom(keyPair.getPublicKey)
    (privateKey, publicKey)
  }

  override def sign(message: ByteString, privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  override def sign(message: Seq[Byte], privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  private def sign(message: Array[Byte], privateKey: Array[Byte]): ED25519Signature = {
    val signature = curve25519.calculateSignature(privateKey, message)
    ED25519Signature.unsafeFrom(signature)
  }

  override def verify(message: ByteString,
                      signature: ED25519Signature,
                      publicKey: ED25519PublicKey): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  override def verify(message: Seq[Byte],
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
