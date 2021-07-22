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

import org.alephium.protocol.PublicKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.serialize
import org.alephium.util.Base58

sealed trait Address {
  def networkType: NetworkType

  def lockupScript: LockupScript

  def toBase58: String = networkType.prefix ++ Base58.encode(serialize(lockupScript))

  override def toString: String = toBase58
}

object Address {
  final case class Asset(networkType: NetworkType, lockupScript: LockupScript.Asset)
      extends Address {
    def groupIndex(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
  }
  final case class Contract(networkType: NetworkType, lockupScript: LockupScript.P2C)
      extends Address

  def from(networkType: NetworkType, lockupScript: LockupScript): Address = {
    lockupScript match {
      case e: LockupScript.Asset => Asset(networkType, e)
      case e: LockupScript.P2C   => Contract(networkType, e)
    }
  }

  def fromBase58(input: String, expected: NetworkType): Option[Address] = {
    for {
      (networkType, lockupScriptBase58) <- NetworkType.decode(input)
      if networkType == expected
      lockupScript <- LockupScript.fromBase58(lockupScriptBase58)
    } yield from(networkType, lockupScript)
  }

  def asset(input: String, expected: NetworkType): Option[Address.Asset] = {
    fromBase58(input, expected) match {
      case Some(address: Asset) => Some(address)
      case _                    => None
    }
  }

  def extractLockupScript(address: String): Option[LockupScript] = {
    for {
      (_, lockupScriptBase58) <- NetworkType.decode(address)
      lockupScript            <- LockupScript.fromBase58(lockupScriptBase58)
    } yield lockupScript
  }

  def p2pkh(networkType: NetworkType, publicKey: PublicKey): Address.Asset =
    Asset(networkType, LockupScript.p2pkh(publicKey))
}
