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

import org.alephium.crypto.{SecP256K1PublicKey, SecP256R1PublicKey}
import org.alephium.protocol.model.ScriptHint
import org.alephium.serde._
import org.alephium.util.DjbHash

sealed trait PublicKeyLike {
  def bytes: ByteString
  def keyType: PublicKeyLike.KeyType

  lazy val scriptHint: ScriptHint = ScriptHint.fromHash(DjbHash.intHash(bytes))
}

object PublicKeyLike {
  sealed trait KeyType

  implicit val keyTypeSerde: Serde[KeyType] = new Serde[KeyType] {
    def serialize(input: KeyType): ByteString = input match {
      case SecP256K1 => ByteString(0)
      case Passkey   => ByteString(1)
    }

    def _deserialize(input: ByteString): SerdeResult[Staging[KeyType]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(0, rest) => Right(Staging(SecP256K1, rest))
        case Staging(1, rest) => Right(Staging(Passkey, rest))
        case Staging(n, _)    => Left(SerdeError.wrongFormat(s"Invalid public key type $n"))
      }
    }
  }

  implicit val serde: Serde[PublicKeyLike] = new Serde[PublicKeyLike] {
    override def serialize(input: PublicKeyLike): ByteString =
      keyTypeSerde.serialize(input.keyType) ++ input.bytes

    override def _deserialize(input: ByteString): SerdeResult[Staging[PublicKeyLike]] = {
      keyTypeSerde._deserialize(input).flatMap {
        case Staging(SecP256K1, rest) =>
          serdeImpl[SecP256K1PublicKey]._deserialize(rest).map(_.mapValue(SecP256K1.apply))
        case Staging(Passkey, rest) =>
          serdeImpl[SecP256R1PublicKey]._deserialize(rest).map(_.mapValue(Passkey.apply))
      }
    }
  }

  final case class SecP256K1(publicKey: SecP256K1PublicKey) extends PublicKeyLike {
    def bytes: ByteString = publicKey.bytes
    def keyType: KeyType  = SecP256K1
  }
  object SecP256K1 extends KeyType

  final case class Passkey(publicKey: SecP256R1PublicKey) extends PublicKeyLike {
    def bytes: ByteString = publicKey.bytes
    def keyType: KeyType  = Passkey
  }
  object Passkey extends KeyType
}
