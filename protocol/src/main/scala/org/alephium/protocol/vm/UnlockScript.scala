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

package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.protocol.PublicKey
import org.alephium.serde._
import org.alephium.util.AVector

sealed trait UnlockScript

object UnlockScript {
  implicit val serde: Serde[UnlockScript] = {
    implicit val tuple: Serde[(PublicKey, Int)] = Serde.tuple2[PublicKey, Int]

    val p2mpkhSerde: Serde[P2MPKH] =
      Serde
        .forProduct1[AVector[(PublicKey, Int)], P2MPKH](P2MPKH.apply, t => t.indexedPublicKeys)
    val p2shSerde: Serde[P2SH] = Serde.forProduct2(P2SH, t => (t.script, t.params))

    new Serde[UnlockScript] {
      override def serialize(input: UnlockScript): ByteString = {
        input match {
          case p2pkh: P2PKH   => ByteString(0) ++ serdeImpl[PublicKey].serialize(p2pkh.publicKey)
          case p2mpkh: P2MPKH => ByteString(1) ++ p2mpkhSerde.serialize(p2mpkh)
          case p2sh: P2SH     => ByteString(2) ++ p2shSerde.serialize(p2sh)
        }
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[UnlockScript]] = {
        byteSerde._deserialize(input).flatMap {
          case Staging(0, content) =>
            serdeImpl[PublicKey]._deserialize(content).map(_.mapValue(P2PKH))
          case Staging(1, content) => p2mpkhSerde._deserialize(content)
          case Staging(2, content) => p2shSerde._deserialize(content)
          case Staging(n, _) => Left(SerdeError.wrongFormat(s"Invalid unlock script prefix $n"))
        }
      }
    }
  }

  def validateP2mpkh(unlock: UnlockScript.P2MPKH): Boolean = {
    (0 until (unlock.indexedPublicKeys.length - 1)).forall { i =>
      val index = unlock.indexedPublicKeys(i)._2
      index >= 0 && unlock.indexedPublicKeys(i + 1)._2 > index
    }
  }

  def p2pkh(publicKey: PublicKey): P2PKH                           = P2PKH(publicKey)
  def p2mpkh(indexedPublicKeys: AVector[(PublicKey, Int)]): P2MPKH = P2MPKH(indexedPublicKeys)
  def p2sh(script: StatelessScript, params: AVector[Val]): P2SH    = P2SH(script, params)

  final case class P2PKH(publicKey: PublicKey)                          extends UnlockScript
  final case class P2MPKH(indexedPublicKeys: AVector[(PublicKey, Int)]) extends UnlockScript
  final case class P2SH(script: StatelessScript, params: AVector[Val])  extends UnlockScript
}
