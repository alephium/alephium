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
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params._
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.bouncycastle.math.ec.custom.sec.SecP256R1Curve

import org.alephium.serde.RandomBytes

// TODO: Secp256R1 and Secp256K1 have a lot of duplicated code. We need to reduce the duplicate code once Secp256R1 is finalized.
final class SecP256R1PrivateKey(val bytes: ByteString) extends PrivateKey {
  def length: Int = SecP256R1PrivateKey.length

  def publicKey: SecP256R1PublicKey = {
    val bigInt    = new BigInteger(1, bytes.toArray)
    val publicKey = SecP256R1.params.getG.multiply(bigInt).getEncoded(true)
    SecP256R1PublicKey.unsafe(ByteString.fromArrayUnsafe(publicKey))
  }
}

object SecP256R1PrivateKey
    extends RandomBytes.Companion[SecP256R1PrivateKey](
      bs => {
        assume(bs.length == 32)
        new SecP256R1PrivateKey(bs)
      },
      _.bytes
    ) {
  def length: Int = 32
}

final class SecP256R1PublicKey(val bytes: ByteString) extends PublicKey {
  def length: Int = SecP256R1PublicKey.length
}

object SecP256R1PublicKey
    extends RandomBytes.Companion[SecP256R1PublicKey](
      bs => {
        assume(bs.length == 33)
        new SecP256R1PublicKey(bs)
      },
      _.bytes
    ) {
  // scalastyle:off magic.number
  def length: Int = 33
  // scalastyle:on magic.number
}

final class SecP256R1Signature(val bytes: ByteString) extends Signature {
  def length: Int = SecP256R1Signature.length
}

object SecP256R1Signature
    extends RandomBytes.Companion[SecP256R1Signature](
      bs => {
        assume(bs.length == 64)
        new SecP256R1Signature(bs)
      },
      _.bytes
    ) {
  // scalastyle:off magic.number
  def length: Int = 64
  // scalastyle:on magic.number

  def from(r: BigInteger, s: BigInteger): SecP256R1Signature = {
    val signature = Array.ofDim[Byte](length)
    val rArray    = r.toByteArray.dropWhile(_ == 0.toByte)
    val sArray    = s.toByteArray // s is canonical, so no need to drop the sign bit
    System.arraycopy(rArray, 0, signature, 32 - rArray.length, rArray.length)
    System.arraycopy(sArray, 0, signature, 64 - sArray.length, sArray.length)
    SecP256R1Signature.unsafe(ByteString.fromArrayUnsafe(signature))
  }

  def decode(signature: Array[Byte]): (BigInteger, BigInteger) = {
    assume(signature.length == length)
    val r = new BigInteger(1, signature.take(32))
    val s = new BigInteger(1, signature.takeRight(32))
    (r, s)
  }
}

trait SecP256R1CurveCommon {
  val params: X9ECParameters = CustomNamedCurves.getByName("secp256r1")

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  val curve: SecP256R1Curve = params.getCurve.asInstanceOf[SecP256R1Curve]

  val domain: ECDomainParameters =
    new ECDomainParameters(curve, params.getG, params.getN, params.getH)

  val halfCurveOrder: BigInteger = params.getN.shiftRight(1)
}

object SecP256R1
    extends SecP256R1CurveCommon
    with SignatureSchema[SecP256R1PrivateKey, SecP256R1PublicKey, SecP256R1Signature] {
  private def isValidPrivateKey(key: SecP256R1PrivateKey): Boolean = {
    val bigInt = new BigInteger(1, key.bytes.toArray)
    bigInt.compareTo(params.getN) < 0
  }

  @scala.annotation.tailrec
  def generatePriPub(): (SecP256R1PrivateKey, SecP256R1PublicKey) = {
    val privateKey = SecP256R1PrivateKey.generate
    if (isValidPrivateKey(privateKey)) {
      (privateKey, privateKey.publicKey)
    } else {
      generatePriPub()
    }
  }

  @scala.annotation.tailrec
  def secureGeneratePriPub(): (SecP256R1PrivateKey, SecP256R1PublicKey) = {
    val privateKey = SecP256R1PrivateKey.secureGenerate
    if (isValidPrivateKey(privateKey)) {
      (privateKey, privateKey.publicKey)
    } else {
      secureGeneratePriPub()
    }
  }

  def sign(message: Array[Byte], privateKey: Array[Byte]): SecP256R1Signature = {
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()))
    val d      = new BigInteger(1, privateKey)
    signer.init(true, new ECPrivateKeyParameters(d, domain))
    val Array(r, s) = signer.generateSignature(message)
    SecP256R1Signature.from(r, canonicalize(s))
  }

  @inline private def isCanonical(s: BigInteger): Boolean = {
    s.compareTo(halfCurveOrder) <= 0
  }

  @inline def canonicalize(s: BigInteger): BigInteger = {
    if (isCanonical(s)) s else params.getN.subtract(s)
  }

  def verify(message: Array[Byte], signature: Array[Byte], publicKey: Array[Byte]): Boolean = {
    val (r, s) = SecP256R1Signature.decode(signature)
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
}
