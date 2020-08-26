package org.alephium.crypto

import akka.util.ByteString

import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

trait PrivateKey extends RandomBytes

trait PublicKey extends RandomBytes

trait Signature extends RandomBytes

trait SignatureSchema[D <: PrivateKey, Q <: PublicKey, S <: Signature] {

  def generatePriPub(): (D, Q)

  def sign(message: ByteString, privateKey: D): S = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  def sign(message: AVector[Byte], privateKey: D): S = {
    sign(message.toArray, privateKey.bytes.toArray)
  }

  protected def sign(message: Array[Byte], privateKey: Array[Byte]): S

  def verify(message: ByteString, signature: S, publicKey: Q): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  def verify(message: AVector[Byte], signature: S, publicKey: Q): Boolean = {
    verify(message.toArray, signature.bytes.toArray, publicKey.bytes.toArray)
  }

  protected def verify(message: Array[Byte],
                       signature: Array[Byte],
                       publicKey: Array[Byte]): Boolean
}
