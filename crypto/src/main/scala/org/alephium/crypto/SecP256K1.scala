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

import scala.util.control.NonFatal

import akka.util.ByteString
import org.bouncycastle.asn1.x9.{X9ECParameters, X9IntegerConverter}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params._
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.{ECAlgorithms, ECPoint}
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve

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
    extends RandomBytes.Companion[SecP256K1PrivateKey](
      bs => {
        assume(bs.length == 32)
        new SecP256K1PrivateKey(bs)
      },
      _.bytes
    ) {
  override def length: Int = 32
}

// public key should be compressed, but the format is not checked until signature verification
class SecP256K1PublicKey(val bytes: ByteString) extends PublicKey {
  lazy val unsafePoint: ECPoint = SecP256K1.point(bytes)

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def toEthAddress(): ByteString = {
    val keyEncoded = ByteString(unsafePoint.getEncoded(false)).tail
    Keccak256.hash(keyEncoded).bytes.drop(12)
  }
}

object SecP256K1PublicKey
    extends RandomBytes.Companion[SecP256K1PublicKey](
      bs => {
        assume(bs.length == 33)
        new SecP256K1PublicKey(bs)
      },
      _.bytes
    ) {
  override def length: Int = 33
}

class SecP256K1Signature(val bytes: ByteString) extends Signature

object SecP256K1Signature
    extends RandomBytes.Companion[SecP256K1Signature](
      bs => {
        assume(bs.length == 64)
        new SecP256K1Signature(bs)
      },
      _.bytes
    ) {
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

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  val curve: SecP256K1Curve = params.getCurve.asInstanceOf[SecP256K1Curve]

  val domain: ECDomainParameters =
    new ECDomainParameters(curve, params.getG, params.getN, params.getH)

  val halfCurveOrder: BigInteger = params.getN.shiftRight(1)

  def point(bytes: ByteString): ECPoint = curve.decodePoint(bytes.toArray)

  override def generatePriPub(): (SecP256K1PrivateKey, SecP256K1PublicKey) = {
    val privateKey = SecP256K1PrivateKey.generate
    (privateKey, privateKey.publicKey)
  }

  override def secureGeneratePriPub(): (SecP256K1PrivateKey, SecP256K1PublicKey) = {
    val privateKey = SecP256K1PrivateKey.secureGenerate
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

  override def verify(
      message: Array[Byte],
      signature: Array[Byte],
      publicKey: Array[Byte]
  ): Boolean = {
    val (r, s) = SecP256K1Signature.decode(signature)
    isCanonical(s) && {
      try {
        val signer         = new ECDSASigner
        val publicKeyPoint = curve.decodePoint(publicKey)
        signer.init(false, new ECPublicKeyParameters(publicKeyPoint, domain))
        signer.verifySignature(message, r, s)
      } catch {
        case NonFatal(_) => false
      }
    }
  }

  /** Recover and return the eth address that generated the signature.
    * The code is adapted from ETH Besu client.
    *
    * @param messageHash hash of the data that was signed
    * @param sigBytes 65 bytes for (r 32Bytes, s 32Bytes, v 1Byte)
    * @return
    */
  def ethEcRecover(
      messageHash: ByteString,
      sigBytes: ByteString
  ): Option[ByteString] = {
    try {
      Some(ethEcRecoverUnsafe(messageHash, sigBytes))
    } catch {
      case NonFatal(_) => None
    }
  }

  // scalastyle:off method.length
  @SuppressWarnings(
    Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.TraversableOps")
  )
  private[crypto] def ethEcRecoverUnsafe(
      messageHash: ByteString,
      sigBytes: ByteString
  ): ByteString = {
    require(messageHash.length == 32, "Invalid message hash length")
    require(sigBytes.length == 65, "Invalid sig data length")
    val recId = (sigBytes.last & 0xff) - 27
    require(recId == 0 || recId == 1, "Invalid v, 27/28 expected")
    val r = new BigInteger(1, sigBytes.take(32).toArray)
    val s = new BigInteger(1, sigBytes.slice(32, 64).toArray)
    // 1.0 For j from 0 to h   (h == recId here and the loop is outside this function)
    //   1.1 Let x = r + jn
    val n = params.getN; // Curve order.
    require(r.signum() == 1 && r.compareTo(n) < 0, "Invalid r") // 0 < r < order
    require(s.signum() == 1 && s.compareTo(n) < 0, "Invalid s") // 0 < r < order
    val i = BigInteger.valueOf(recId.toLong / 2)
    val x = r.add(i.multiply(n))
    //   1.2. Convert the integer x to an octet string X of length mlen using the conversion
    //        routine specified in Section 2.3.7, where mlen = ⌈(log2 p)/8⌉ or mlen = ⌈m/8⌉.
    //   1.3. Convert the octet string (16 set binary digits)||X to an elliptic curve point R
    //        using the conversion routine specified in Section 2.3.4. If this conversion
    //        routine outputs "invalid", then do another iteration of Step 1.
    //
    // More concisely, what these points mean is to use X as a compressed public key.
    val prime = curve.getQ
    require(
      x.compareTo(prime) < 0,
      "Cannot have point co-ordinates larger than this as everything takes place modulo Q."
    )
    // Compressed keys require you to know an extra bit of data about the y-coord as there are
    // two possibilities. So it's encoded in the recId.
    val R = decompressKey(x, (recId & 1) == 1);
    //   1.4. If nR != point at infinity, then do another iteration of Step 1 (callers
    //        responsibility).
    require(R.multiply(n).isInfinity, "point cannot be at infinity")
    //   1.5. Compute e from M using Steps 2 and 3 of ECDSA signature verification.
    val e = new BigInteger(1, messageHash.toArray);
    //   1.6. For k from 1 to 2 do the following.   (loop is outside this function via
    //        iterating recId)
    //   1.6.1. Compute a candidate public key as:
    //               Q = mi(r) * (sR - eG)
    //
    // Where mi(x) is the modular multiplicative inverse. We transform this into the following:
    //               Q = (mi(r) * s ** R) + (mi(r) * -e ** G)
    // Where -e is the modular additive inverse of e, that is z such that z + e = 0 (mod n).
    // In the above equation ** is point multiplication and + is point addition (the EC group
    // operator).
    //
    // We can find the additive inverse by subtracting e from zero then taking the mod. For
    // example the additive inverse of 3 modulo 11 is 8 because 3 + 8 mod 11 = 0, and
    // -3 mod 11 = 8.
    val eInv     = BigInteger.ZERO.subtract(e).mod(n);
    val rInv     = r.modInverse(n);
    val srInv    = rInv.multiply(s).mod(n);
    val eInvrInv = rInv.multiply(eInv).mod(n);
    val q        = ECAlgorithms.sumOfTwoMultiplies(params.getG, eInvrInv, R, srInv);

    val qBytes = q.getEncoded(false)
    // We remove the prefix
    val publiKey = ByteString.fromArrayUnsafe(qBytes, 1, qBytes.length - 1)
    Keccak256.hash(publiKey).bytes.drop(12)
  }
  // scalastyle:on method.length

  private def decompressKey(xBN: BigInteger, yBit: Boolean): ECPoint = {
    val x9      = new X9IntegerConverter()
    val compEnc = x9.integerToBytes(xBN, 1 + x9.getByteLength(curve));
    if (yBit) compEnc(0) = 0x03 else compEnc(0) = 0x02
    curve.decodePoint(compEnc)
  }
}
