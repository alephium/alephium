package org.alephium

import org.alephium.crypto._

package object protocol {
  type Hash = Blake2b
  val Hash: Blake2b.type = Blake2b

  type PublicKey = SecP256K1PublicKey
  val PublicKey: SecP256K1PublicKey.type = SecP256K1PublicKey

  type PrivateKey = SecP256K1PrivateKey
  val PrivateKey: SecP256K1PrivateKey.type = SecP256K1PrivateKey

  type Signature = SecP256K1Signature
  val Signature: SecP256K1Signature.type = SecP256K1Signature

  val SignatureSchema: SecP256K1.type = SecP256K1
}
