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

package org.alephium.api.model

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AddressLike, GroupIndex}
import org.alephium.protocol.vm.{LockupScript, UnlockScript}

trait BuildGrouplessTx {
  val fromAddress: AddressLike

  def groupIndex()(implicit config: GroupConfig): Either[String, GroupIndex]

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def getFromAddress()(implicit config: GroupConfig): Either[String, Address.Asset] = {
    fromAddress.getAddress() match {
      case address: Address.Asset =>
        Right(address)
      case _: Address.Contract =>
        Left(s"Expect asset address, but was contract address: $fromAddress")
    }
  }

  def getLockPair()(implicit config: GroupConfig): Either[String, (LockupScript.P2PK, UnlockScript)]

  val notGrouplessAddressError =
    s"Invalid from address: `$fromAddress`, expected a groupless address"
}
