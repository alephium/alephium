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

import org.alephium.crypto.SecP256K1PublicKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.serde._
import org.alephium.util.Bytes

sealed trait PublicKeyType {
  def bytes: ByteString

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def defaultGroup(implicit groupConfig: GroupConfig): GroupIndex = {
    GroupIndex.unsafe(Bytes.toPosInt(bytes.last) % groupConfig.groups)
  }
}

object PublicKeyType {
  implicit val serde: Serde[PublicKeyType] = new Serde[PublicKeyType] {
    override def serialize(input: PublicKeyType): ByteString = input match {
      case SecP256K1(publicKey) =>
        ByteString(0) ++ serdeImpl[SecP256K1PublicKey].serialize(publicKey)
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[PublicKeyType]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(0, rest) =>
          serdeImpl[SecP256K1PublicKey]._deserialize(rest).map(_.mapValue(SecP256K1.apply))
        case Staging(n, _) => Left(SerdeError.wrongFormat(s"Invalid public key type $n"))
      }
    }
  }

  final case class SecP256K1(publicKey: SecP256K1PublicKey) extends PublicKeyType {
    def bytes: ByteString = publicKey.bytes
  }
}
