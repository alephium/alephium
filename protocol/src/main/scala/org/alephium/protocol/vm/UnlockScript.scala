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
  val p2shSerde: Serde[P2SH] = Serde.forProduct2(P2SH(_, _), t => (t.script, t.params))
  val p2sSerde: Serde[P2S]   = Serde.forProduct1(P2S(_), t => t.params)
  implicit val serde: Serde[UnlockScript] = new Serde[UnlockScript] {
    override def serialize(input: UnlockScript): ByteString = {
      input match {
        case ppkh: P2PKH => ByteString(0) ++ serdeImpl[PublicKey].serialize(ppkh.publicKey)
        case psh: P2SH   => ByteString(1) ++ p2shSerde.serialize(psh)
        case ps: P2S     => ByteString(2) ++ p2sSerde.serialize(ps)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[UnlockScript]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(0, content) =>
          serdeImpl[PublicKey]._deserialize(content).map(_.mapValue(P2PKH(_)))
        case Staging(1, content) => p2shSerde._deserialize(content)
        case Staging(2, content) => p2sSerde._deserialize(content)
        case Staging(n, _)       => Left(SerdeError.wrongFormat(s"Invalid unlock script prefix $n"))
      }
    }
  }

  def p2pkh(publicKey: PublicKey): P2PKH                        = P2PKH(publicKey)
  def p2sh(script: StatelessScript, params: AVector[Val]): P2SH = P2SH(script, params)
  def p2s(params: AVector[Val]): P2S                            = P2S(params)

  final case class P2PKH(publicKey: PublicKey)                             extends UnlockScript
  final case class P2SH(script: StatelessScript, val params: AVector[Val]) extends UnlockScript
  final case class P2S(params: AVector[Val])                               extends UnlockScript
}
