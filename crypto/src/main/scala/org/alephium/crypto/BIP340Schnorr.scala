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
import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import akka.util.ByteString
import org.bouncycastle.math.ec.ECPoint

import org.alephium.serde.RandomBytes
import org.alephium.util.U256

//scalastyle:off magic.number

class BIP340SchnorrPrivateKey(val bytes: ByteString) extends PrivateKey {
  private def getBigInt() = new BigInteger(1, bytes.toArray)

  def publicKey: BIP340SchnorrPublicKey = {
    val d      = getBigInt()
    val xCoord = BIP340Schnorr.params.getG.multiply(d).normalize().getAffineXCoord.getEncoded
    new BIP340SchnorrPublicKey(ByteString.fromArrayUnsafe(xCoord))
  }
}

object BIP340SchnorrPrivateKey
    extends RandomBytes.Companion[BIP340SchnorrPrivateKey](
      bs => BIP340Schnorr.privateKeyUnsafe(bs),
      _.bytes
    ) {
  override def length: Int = 32
}

class BIP340SchnorrPublicKey(val bytes: ByteString) extends PublicKey

object BIP340SchnorrPublicKey
    extends RandomBytes.Companion[BIP340SchnorrPublicKey](
      bs => {
        assume(bs.length == 32)
        new BIP340SchnorrPublicKey(bs)
      },
      _.bytes
    ) {
  override def length: Int = 32
}

class BIP340SchnorrSignature(val bytes: ByteString) extends Signature

object BIP340SchnorrSignature
    extends RandomBytes.Companion[BIP340SchnorrSignature](
      bs => {
        assume(bs.length == 64)
        new BIP340SchnorrSignature(bs)
      },
      _.bytes
    ) {

  override def length: Int = 64
}

object BIP340Schnorr
    extends SecP256K1CurveCommon
    with SignatureSchema[BIP340SchnorrPrivateKey, BIP340SchnorrPublicKey, BIP340SchnorrSignature] {
  def privateKeyUnsafe(bytes: ByteString): BIP340SchnorrPrivateKey = {
    assume(bytes.length == 32)
    val d    = new BigInteger(1, bytes.toArrayUnsafe())
    val dMod = d.mod(params.getN)
    new BIP340SchnorrPrivateKey(U256.unsafe(dMod).toBytes)
  }

  def generatePriPub(): (BIP340SchnorrPrivateKey, BIP340SchnorrPublicKey) = {
    val privateKey = BIP340SchnorrPrivateKey.generate
    (privateKey, privateKey.publicKey)
  }

  override def secureGeneratePriPub(): (BIP340SchnorrPrivateKey, BIP340SchnorrPublicKey) = {
    val privateKey = BIP340SchnorrPrivateKey.secureGenerate
    (privateKey, privateKey.publicKey)
  }

  override protected def sign(
      message: Array[Byte],
      privateKey: Array[Byte]
  ): BIP340SchnorrSignature = {
    sign(message, privateKey, Byte32.generate.bytes.toArrayUnsafe())
  }

  private[crypto] def sign(
      message: Array[Byte],
      privateKey: Array[Byte],
      auxRandom: Array[Byte]
  ): BIP340SchnorrSignature = {
    require(message.length == 32)
    val d0 = new BigInteger(1, privateKey)
    require(d0.compareTo(BigInteger.ZERO) > 0 && d0.compareTo(params.getN) < 0)
    require(auxRandom.length == 32)
    val P      = params.getG.multiply(d0).normalize()
    val pBytes = ByteString.fromArrayUnsafe(P.getAffineXCoord.getEncoded)
    require(!P.isInfinity)
    val d = if (P.getAffineYCoord.testBitZero()) {
      params.getN.subtract(d0)
    } else {
      d0
    }
    val t      = xorBytes(toByte32(d), taggedHash(auxTag, auxRandom).bytes)
    val k0Msg  = t ++ pBytes ++ ByteString.fromArrayUnsafe(message)
    val k0Hash = taggedHash(nonceTag, k0Msg.toArrayUnsafe())
    val k0     = new BigInteger(1, k0Hash.bytes.toArrayUnsafe()).mod(params.getN)
    require(k0 != BigInteger.ZERO)
    val R = params.getG.multiply(k0).normalize()
    require(!R.isInfinity)
    val rBytes = ByteString.fromArrayUnsafe(R.getAffineXCoord.getEncoded)
    val k = if (R.getAffineYCoord.testBitZero()) {
      params.getN.subtract(k0)
    } else {
      k0
    }
    val eMsg  = rBytes ++ pBytes ++ ByteString.fromArrayUnsafe(message)
    val eHash = taggedHash(challengeTag, eMsg.toArrayUnsafe())
    val e     = new BigInteger(1, eHash.bytes.toArrayUnsafe()).mod(params.getN)

    val sig = rBytes ++ toByte32(k.add(e.multiply(d)).mod(params.getN))
    BIP340SchnorrSignature.unsafe(sig)
  }

  private def xorBytes(bytes0: ByteString, bytes1: ByteString): ByteString = {
    assume(bytes0.length == bytes1.length)
    val result = Array.tabulate(bytes0.length) { k =>
      (bytes0(k) ^ bytes1(k)).toByte
    }
    ByteString.fromArrayUnsafe(result)
  }

  private[crypto] def toByte32(n: BigInteger): ByteString = {
    U256.unsafe(n).toBytes
  }

  private lazy val auxTag =
    Sha256.hash(ByteString.fromString("BIP0340/aux", StandardCharsets.UTF_8))
  private lazy val nonceTag =
    Sha256.hash(ByteString.fromString("BIP0340/nonce", StandardCharsets.UTF_8))
  private lazy val challengeTag =
    Sha256.hash(ByteString.fromString("BIP0340/challenge", StandardCharsets.UTF_8))
  private def taggedHash(tagHash: Sha256, message: Array[Byte]): Sha256 = {
    Sha256
      .hash(tagHash.bytes ++ tagHash.bytes ++ ByteString.fromArrayUnsafe(message))
  }

  override protected def verify(
      message: Array[Byte],
      signature: Array[Byte],
      publicKey: Array[Byte]
  ): Boolean = try {
    require(message.length == 32)
    require(signature.length == 64)
    require(publicKey.length == 32)
    val P = liftX(publicKey)
    val r = new BigInteger(1, signature, 0, 32)
    val s = new BigInteger(1, signature, 32, 32)
    if (r.compareTo(curve.getQ) >= 0 || s.compareTo(params.getN) >= 0) {
      false
    } else {
      val eMsg = ByteString.fromArrayUnsafe(signature, 0, 32) ++
        ByteString.fromArrayUnsafe(publicKey) ++ ByteString.fromArrayUnsafe(message)
      val eHash = taggedHash(challengeTag, eMsg.toArrayUnsafe())
      val e     = new BigInteger(1, eHash.bytes.toArrayUnsafe()).mod(params.getN)
      val R     = params.getG.multiply(s).add(P.multiply(params.getN.subtract(e))).normalize()
      if (R.isInfinity || R.getAffineYCoord.testBitZero() || R.getAffineXCoord.toBigInteger != r) {
        false
      } else {
        true
      }
    }
  } catch {
    case NonFatal(_) => false
  }

  private val three         = BigInteger.valueOf(3)
  private val four          = BigInteger.valueOf(4)
  private val seven         = BigInteger.valueOf(7)
  private lazy val ySqOrder = curve.getQ.add(BigInteger.ONE).divide(four)
  private def liftX(xRaw: Array[Byte]): ECPoint = {
    val x = new BigInteger(1, xRaw)
    require(x.compareTo(curve.getQ) < 0)
    val ySq = x.modPow(three, curve.getQ).add(seven).mod(curve.getQ)
    val y0  = ySq.modPow(ySqOrder, curve.getQ)
    require(y0.modPow(BigInteger.TWO, curve.getQ) == ySq)
    val y = if (y0.testBit(0)) {
      curve.getQ.subtract(y0)
    } else {
      y0
    }
    curve.createPoint(x, y)
  }
}
