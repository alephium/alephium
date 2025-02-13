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
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.{LockupScript, UnlockScript}

trait BuildGrouplessTx {
  val fromAddress: String

  var decodedAddress: Option[Address.Asset] = None
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def getFromAddress()(implicit config: GroupConfig): Either[String, Address.Asset] = {
    if (decodedAddress.isDefined) {
      Right(decodedAddress.get)
    } else {
      Address.fromBase58(fromAddress) match {
        case Some(address: Address.Asset) =>
          decodedAddress = Some(address)
          Right(address)
        case Some(_: Address.Contract) =>
          Left(s"Expect asset address, but was contract address: $fromAddress")
        case None =>
          Left(s"Unable to decode address from $fromAddress")
      }
    }
  }

  def lockPair(implicit config: GroupConfig): Either[String, (LockupScript.P2PK, UnlockScript)] = {
    getFromAddress() match {
      case Right(address) =>
        address.lockupScript match {
          case lock: LockupScript.P2PK => Right((lock, UnlockScript.P2PK(lock.publicKey.keyType)))
          case _ => Left(s"Invalid from address: $address, expected a groupless address")
        }
      case Left(error) => Left(error)
    }
  }
}
