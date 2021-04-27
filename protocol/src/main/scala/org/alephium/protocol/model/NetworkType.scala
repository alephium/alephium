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

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.serde.intSerde
import org.alephium.util.AVector

sealed trait NetworkType {
  def name: String
  def prefix: String
  lazy val magicBytes: ByteString = intSerde.serialize(Hash.hash("ALF" ++ prefix).toRandomIntUnsafe)
}

object NetworkType {
  case object Mainnet extends NetworkType {
    val name: String   = "mainnet"
    val prefix: String = "M"
  }
  case object Testnet extends NetworkType {
    val name: String   = "testnet"
    val prefix: String = "T"
  }
  case object Devnet extends NetworkType {
    val name: String   = "devnet"
    val prefix: String = "D"
  }

  val all: AVector[NetworkType] = AVector(Mainnet, Testnet, Devnet)

  def fromName(name: String): Option[NetworkType] = all.find(_.name == name)

  def decode(address: String): Option[(NetworkType, String)] = {
    if (address.startsWith(Mainnet.prefix)) {
      Some(Mainnet -> address.drop(Mainnet.prefix.length))
    } else if (address.startsWith(Testnet.prefix)) {
      Some(Testnet -> address.drop(Testnet.prefix.length))
    } else if (address.startsWith(Devnet.prefix)) {
      Some(Devnet -> address.drop(Devnet.prefix.length))
    } else {
      None
    }
  }
}
