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

package org.alephium.protocol.message

sealed trait ProtocolVersion

object ProtocolVersion {
  def fromClientId(clientId: String): Option[ProtocolVersion] = {
    clientId.split("/") match {
      case Array(_, _, _)            => Some(ProtocolV1)
      case Array(_, _, _, "sync-v1") => Some(ProtocolV1)
      case Array(_, _, _, "sync-v2") => Some(ProtocolV2)
      case _                         => None
    }
  }
}

case object ProtocolV1 extends ProtocolVersion {
  override def toString: String = "sync-v1"
}
case object ProtocolV2 extends ProtocolVersion {
  override def toString: String = "sync-v2"
}
