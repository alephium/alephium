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

package org.alephium.protocol.model

import org.alephium.serde._

final case class NetworkId(id: Byte) extends AnyVal {
  // network type will only be used to load the correct config file
  // Devnet or any other network default to testnet config
  def networkType: NetworkId.Type = id match {
    case 0 => NetworkId.MainNetType
    case 1 => NetworkId.TestNetType
    case _ => NetworkId.TestNetType
  }

  def nodeFolder: String = id match {
    case 0 => "mainnet"
    case 1 => "testnet"
    case _ => s"network-$id"
  }
}

object NetworkId {
  val AlephiumMainNet: NetworkId = NetworkId(0)
  val AlephiumTestNet: NetworkId = NetworkId(1)

  implicit val serde: Serde[NetworkId] = byteSerde.xmap(NetworkId.apply, _.id)

  def from(id: Int): Option[NetworkId] =
    Option.when(id >= Byte.MinValue && id <= Byte.MaxValue)(NetworkId(id.toByte))

  sealed trait Type {
    def name: String
    override def toString: String = name
  }
  case object MainNetType extends Type { val name: String = "mainnet" }
  case object TestNetType extends Type { val name: String = "testnet" }
}
