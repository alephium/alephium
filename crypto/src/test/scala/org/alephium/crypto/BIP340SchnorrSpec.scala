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

import scala.io.Source

import org.alephium.util.{AlephiumSpec, Hex}

class BIP340SchnorrSpec extends AlephiumSpec {
  it should "test parameters" in {
    ((BIP340Schnorr.curve.getQ.bitLength() + 7) / 8) is 32
  }

  it should "pass test vectors" in {
    val stream = this.getClass.getResourceAsStream("/bip340.csv")
    val lines  = Source.fromInputStream(stream).getLines().toSeq
    lines.tail.foreach { line =>
      val parts      = line.split(",")
      val privateKey = parsePrivateKey(parts(1))
      val publicKey  = BIP340SchnorrPublicKey.unsafe(Hex.unsafe(parts(2)))
      val auxRand    = parseAuxRand(parts(3))
      val message    = Byte32.unsafe(Hex.unsafe(parts(4)))
      val signature  = BIP340SchnorrSignature.unsafe(Hex.unsafe(parts(5)))
      val result     = parts(6) == "TRUE"

      privateKey.foreach { privateKey =>
        privateKey.publicKey is publicKey
        BIP340Schnorr.sign(
          message.bytes.toArrayUnsafe(),
          privateKey.bytes.toArrayUnsafe(),
          auxRand.value.bytes.toArrayUnsafe()
        ) is signature
      }

      BIP340Schnorr.verify(message.bytes, signature, publicKey) is result
    }
  }

  def parsePrivateKey(input: String): Option[BIP340SchnorrPrivateKey] = {
    if (input.isEmpty) {
      None
    } else {
      Some(BIP340SchnorrPrivateKey.unsafe(Hex.unsafe(input)))
    }
  }

  def parseAuxRand(input: String): Option[Byte32] = {
    if (input.isEmpty) {
      None
    } else {
      Some(Byte32.unsafe(Hex.unsafe(input)))
    }
  }
}
