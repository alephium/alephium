package org.alephium.crypto

import akka.util.ByteString

trait PrivateKey
trait PublicKey
trait Signature

trait SignatureSchema[D <: PrivateKey, Q <: PublicKey, S <: Signature] {

  def seedSize: Int

  def generateKeyPair(): (D, Q)

  def sign(message: ByteString, privateKey: D): S

  def sign(message: Seq[Byte], privateKey: D): S

  def verify(message: ByteString, signature: S, publicKey: Q): Boolean

  def verify(message: Seq[Byte], signature: S, publicKey: Q): Boolean
}
