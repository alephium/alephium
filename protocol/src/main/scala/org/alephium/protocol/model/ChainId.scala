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

final case class ChainId(id: Byte) extends AnyVal {
  // network type will only be used to load the correct config file
  def networkType: ChainId.Type = (id % 3) match {
    case 0 => ChainId.MainNet
    case 1 => ChainId.TestNet
    case 2 => ChainId.DevNet
  }

  def verboseName: String = s"${networkType.name}-$id"
}

object ChainId {
  val AlephiumMainNet: ChainId = ChainId(0)
  val AlephiumTestNet: ChainId = ChainId(1)
  val AlephiumDevNet: ChainId  = ChainId(2)

  implicit val serde: Serde[ChainId] = byteSerde.xmap(ChainId.apply, _.id)

  def from(id: Int): Option[ChainId] =
    Option.when(id >= Byte.MinValue && id <= Byte.MaxValue)(ChainId(id.toByte))

  sealed trait Type {
    def name: String
    override def toString: String = name
  }
  case object MainNet extends Type { val name: String = "mainnet" }
  case object TestNet extends Type { val name: String = "testnet" }
  case object DevNet  extends Type { val name: String = "devnet"  }
}
