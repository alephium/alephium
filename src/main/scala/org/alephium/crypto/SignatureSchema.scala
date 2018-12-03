package org.alephium.crypto

import akka.util.ByteString
import org.alephium.util.Bytes

trait PrivateKey extends Bytes

trait PublicKey extends Bytes

trait Signature extends Bytes

trait SignatureSchema[D <: PrivateKey, Q <: PublicKey, S <: Signature] {

  def generateKeyPair(): (D, Q)

  def sign(message: ByteString, privateKey: D): S

  def sign(message: Seq[Byte], privateKey: D): S

  def verify(message: ByteString, signature: S, publicKey: Q): Boolean

  def verify(message: Seq[Byte], signature: S, publicKey: Q): Boolean
}
