package org.alephium.crypto

import akka.util.ByteString
import org.whispersystems.curve25519.Curve25519

case class ED25519PrivateKey(key: Seq[Byte]) extends PrivateKey

case class ED25519PublicKey(key: Seq[Byte]) extends PublicKey

case class ED25519Signature(signature: Seq[Byte]) extends Signature

object ED25519 extends SignatureSchema[ED25519PrivateKey, ED25519PublicKey, ED25519Signature] {
  override val seedSize = 32

  val curve25519: Curve25519 = Curve25519.getInstance(Curve25519.BEST)

  override def generateKeyPair(): (ED25519PrivateKey, ED25519PublicKey) = {
    val keyPair    = curve25519.generateKeyPair()
    val privateKey = ED25519PrivateKey(keyPair.getPrivateKey)
    val publicKey  = ED25519PublicKey(keyPair.getPublicKey)
    (privateKey, publicKey)
  }

  override def sign(message: ByteString, privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.key.toArray)
  }

  override def sign(message: Seq[Byte], privateKey: ED25519PrivateKey): ED25519Signature = {
    sign(message.toArray, privateKey.key.toArray)
  }

  private def sign(message: Array[Byte], privateKey: Array[Byte]): ED25519Signature = {
    val signature = curve25519.calculateSignature(privateKey, message)
    ED25519Signature(signature)
  }

  override def verify(message: ByteString,
                      signature: ED25519Signature,
                      publicKey: ED25519PublicKey): Boolean = {
    verify(message.toArray, signature.signature.toArray, publicKey.key.toArray)
  }

  override def verify(message: Seq[Byte],
                      signature: ED25519Signature,
                      publicKey: ED25519PublicKey): Boolean = {
    verify(message.toArray, signature.signature.toArray, publicKey.key.toArray)
  }

  private def verify(message: Array[Byte],
                     signature: Array[Byte],
                     publicKey: Array[Byte]): Boolean = {
    curve25519.verifySignature(publicKey, message, signature)
  }
}
