// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.crypto

import java.math.BigInteger

import akka.util.ByteString
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params._
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECCurve, ECPoint}

import org.alephium.serde.RandomBytes

//scalastyle:off magic.number

class SecP256K1PrivateKey(val bytes: ByteString) extends PrivateKey {
  lazy val bigInt = new BigInteger(1, bytes.toArray)

  def isZero: Boolean = bigInt == BigInteger.ZERO

  def publicKey: SecP256K1PublicKey = {
    val rawPublicKey = SecP256K1.params.getG.multiply(bigInt).getEncoded(true)
    SecP256K1PublicKey.unsafe(ByteString.fromArrayUnsafe(rawPublicKey))
  }

  def add(that: SecP256K1PrivateKey): SecP256K1PrivateKey = {
    val result =
      this.bigInt.add(that.bigInt).mod(SecP256K1.params.getN).toByteArray.dropWhile(_ == 0.toByte)
    assume(result.length <= SecP256K1PrivateKey.length)
    val buffer = Array.ofDim[Byte](SecP256K1PrivateKey.length)
    System.arraycopy(result, 0, buffer, buffer.length - result.length, result.length)
    SecP256K1PrivateKey.unsafe(ByteString.fromArrayUnsafe(buffer))
  }
}

object SecP256K1PrivateKey
    extends RandomBytes.Companion[SecP256K1PrivateKey](bs => {
      assume(bs.length == 32)
      new SecP256K1PrivateKey(bs)
    }, _.bytes) {
  override def length: Int = 32
}

class SecP256K1PublicKey(val bytes: ByteString) extends PublicKey {
  lazy val unsafePoint: ECPoint = SecP256K1.point(bytes)
}

object SecP256K1PublicKey
    extends RandomBytes.Companion[SecP256K1PublicKey](bs => {
      assume(bs.length == 33)
      new SecP256K1PublicKey(bs)
    }, _.bytes) {
  override def length: Int = 33
}

class SecP256K1Signature(val bytes: ByteString) extends Signature

object SecP256K1Signature
    extends RandomBytes.Companion[SecP256K1Signature](bs => {
      assume(bs.length == 64)
      new SecP256K1Signature(bs)
    }, _.bytes) {
  override def length: Int = 64

  protected[crypto] def from(r: BigInteger, s: BigInteger): SecP256K1Signature = {
    val signature = Array.ofDim[Byte](length)
    val rArray    = r.toByteArray.dropWhile(_ == 0.toByte)
    val sArray    = s.toByteArray // s is canonical, so no need to drop the sign bit
    System.arraycopy(rArray, 0, signature, 32 - rArray.length, rArray.length)
    System.arraycopy(sArray, 0, signature, 64 - sArray.length, sArray.length)
    SecP256K1Signature.unsafe(ByteString.fromArrayUnsafe(signature))
  }

  protected[crypto] def decode(signature: Array[Byte]): (BigInteger, BigInteger) = {
    assume(signature.length == length)
    val r = new BigInteger(1, signature.take(32))
    val s = new BigInteger(1, signature.takeRight(32))
    (r, s)
  }
}

object SecP256K1
    extends SignatureSchema[SecP256K1PrivateKey, SecP256K1PublicKey, SecP256K1Signature] {
  val params: X9ECParameters = CustomNamedCurves.getByName("secp256k1")

  val curve: ECCurve = params.getCurve

  val domain: ECDomainParameters =
    new ECDomainParameters(curve, params.getG, params.getN, params.getH)

  val halfCurveOrder: BigInteger = params.getN.shiftRight(1)

  def point(bytes: ByteString): ECPoint = curve.decodePoint(bytes.toArray)

  override def generatePriPub(): (SecP256K1PrivateKey, SecP256K1PublicKey) = {
    val privateKey = SecP256K1PrivateKey.generate
    (privateKey, privateKey.publicKey)
  }

  override def sign(message: Array[Byte], privateKey: Array[Byte]): SecP256K1Signature = {
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    val d      = new BigInteger(1, privateKey)
    signer.init(true, new ECPrivateKeyParameters(d, domain))
    val Array(r, s) = signer.generateSignature(message)
    SecP256K1Signature.from(r, canonicalize(s))
  }

  @inline private def isCanonical(s: BigInteger): Boolean = {
    s.compareTo(halfCurveOrder) <= 0
  }

  @inline def canonicalize(s: BigInteger): BigInteger = {
    if (isCanonical(s)) s else params.getN.subtract(s)
  }

  override def verify(message: Array[Byte],
                      signature: Array[Byte],
                      publicKey: Array[Byte]): Boolean = {
    val (r, s) = SecP256K1Signature.decode(signature)
    isCanonical(s) && {
      val signer         = new ECDSASigner
      val publicKeyPoint = curve.decodePoint(publicKey)
      signer.init(false, new ECPublicKeyParameters(publicKeyPoint, domain))
      signer.verifySignature(message, r, s)
    }
  }
}
