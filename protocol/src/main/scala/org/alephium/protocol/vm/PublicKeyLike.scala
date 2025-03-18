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

import org.alephium.crypto._
import org.alephium.crypto.{PublicKey => CryptoPublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.serde._
import org.alephium.util.Bytes

sealed trait PublicKeyLike extends Product with Serializable {
  def publicKey: CryptoPublicKey
  def rawBytes: ByteString = publicKey.bytes

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def defaultGroup(implicit groupConfig: GroupConfig): GroupIndex = {
    GroupIndex.unsafe(Bytes.toPosInt(rawBytes.last) % groupConfig.groups)
  }
}

object PublicKeyLike {
  implicit val serde: Serde[PublicKeyLike] = new Serde[PublicKeyLike] {
    override def serialize(input: PublicKeyLike): ByteString = {
      input match {
        case SecP256K1(publicKey) =>
          ByteString(0) ++ serdeImpl[SecP256K1PublicKey].serialize(publicKey)
        case SecP256R1(publicKey) =>
          ByteString(1) ++ serdeImpl[SecP256R1PublicKey].serialize(publicKey)
        case ED25519(publicKey) =>
          ByteString(2) ++ serdeImpl[ED25519PublicKey].serialize(publicKey)
        case Passkey(publicKey) =>
          ByteString(3) ++ serdeImpl[SecP256R1PublicKey].serialize(publicKey)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[PublicKeyLike]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(0, rest) =>
          serdeImpl[SecP256K1PublicKey]._deserialize(rest).map(_.mapValue(SecP256K1.apply))
        case Staging(1, rest) =>
          serdeImpl[SecP256R1PublicKey]._deserialize(rest).map(_.mapValue(SecP256R1.apply))
        case Staging(2, rest) =>
          serdeImpl[ED25519PublicKey]._deserialize(rest).map(_.mapValue(ED25519.apply))
        case Staging(3, rest) =>
          serdeImpl[SecP256R1PublicKey]._deserialize(rest).map(_.mapValue(Passkey.apply))
        case Staging(n, _) => Left(SerdeError.wrongFormat(s"Invalid public key type $n"))
      }
    }
  }

  final case class SecP256K1(publicKey: SecP256K1PublicKey) extends PublicKeyLike
  final case class SecP256R1(publicKey: SecP256R1PublicKey) extends PublicKeyLike
  final case class ED25519(publicKey: ED25519PublicKey)     extends PublicKeyLike
  final case class Passkey(publicKey: SecP256R1PublicKey)   extends PublicKeyLike
}
