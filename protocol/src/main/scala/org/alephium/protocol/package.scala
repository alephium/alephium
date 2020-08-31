package org.alephium

import org.alephium.crypto._

package object protocol {
  type Hash = Blake2b
  val Hash: Blake2b.type = Blake2b

  type ALFPublicKey = SecP256K1PublicKey
  val ALFPublicKey: SecP256K1PublicKey.type = SecP256K1PublicKey

  type ALFPrivateKey = SecP256K1PrivateKey
  val ALFPrivateKey: SecP256K1PrivateKey.type = SecP256K1PrivateKey

  type ALFSignature = SecP256K1Signature
  val ALFSignature: SecP256K1Signature.type = SecP256K1Signature

  val ALFSignatureSchema: SecP256K1.type = SecP256K1
}
