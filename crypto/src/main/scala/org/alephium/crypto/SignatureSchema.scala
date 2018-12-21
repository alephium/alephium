package org.alephium.crypto

import org.alephium.serde.RandomBytes
import akka.util.ByteString
import org.alephium.util.AVector

trait PrivateKey extends RandomBytes

trait PublicKey extends RandomBytes

trait Signature extends RandomBytes

trait SignatureSchema[D <: PrivateKey, Q <: PublicKey, S <: Signature] {

  def generatePriPub(): (D, Q)

  def sign(message: ByteString, privateKey: D): S

  def sign(message: AVector[Byte], privateKey: D): S

  def verify(message: ByteString, signature: S, publicKey: Q): Boolean

  def verify(message: AVector[Byte], signature: S, publicKey: Q): Boolean
}
