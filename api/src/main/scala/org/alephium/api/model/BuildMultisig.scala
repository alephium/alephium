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

import akka.util.ByteString

import org.alephium.api.{badRequest, Try}
import org.alephium.crypto.SecP256K1PublicKey
import org.alephium.protocol.PublicKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AddressLike}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{AVector, Hex}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BuildMultisig(
    fromAddress: AddressLike,
    fromPublicKeys: AVector[ByteString],
    destinations: AVector[Destination],
    gas: Option[GasBox] = None,
    gasPrice: Option[GasPrice] = None
) {
  def getFromAddress()(implicit config: GroupConfig): Try[Address.Asset] = {
    fromAddress.getAddress() match {
      case address: Address.Asset =>
        Right(address)
      case address: Address.Contract =>
        Left(badRequest(s"Expect asset address, but was contract address: ${address.toBase58}"))
    }
  }

  def getFromPublicKeys(): Try[AVector[PublicKey]] = {
    fromPublicKeys.foldE(AVector.empty[PublicKey]) { (acc, rawPubKey) =>
      SecP256K1PublicKey.from(rawPubKey) match {
        case Some(publicKey) => Right(acc :+ publicKey)
        case None => Left(badRequest(s"Invalid public key: ${Hex.toHexString(rawPubKey)}"))
      }
    }
  }
}
