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
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.Base58

final case class Address(networkType: NetworkType, lockupScript: LockupScript) {
  def toBase58: String = networkType.prefix ++ Base58.encode(serialize(lockupScript))

  def groupIndex(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex

  override def toString: String = toBase58
}

object Address {
  def fromBase58(input: String, expected: NetworkType): Option[Address] = {
    for {
      (networkType, lockupScriptBase58) <- NetworkType.decode(input)
      if networkType == expected
      lockupScriptRaw <- Base58.decode(lockupScriptBase58)
      lockupScript    <- deserialize[LockupScript](lockupScriptRaw).toOption
    } yield Address(networkType, lockupScript)
  }

  def extractLockupScript(address: String): Option[LockupScript] = {
    for {
      (_, lockupScriptBase58) <- NetworkType.decode(address)
      lockupScriptRaw         <- Base58.decode(lockupScriptBase58)
      lockupScript            <- deserialize[LockupScript](lockupScriptRaw).toOption
    } yield lockupScript
  }

  def p2pkh(networkType: NetworkType, publicKey: PublicKey): Address =
    Address(networkType, LockupScript.p2pkh(publicKey))
}
