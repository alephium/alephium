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

import org.alephium.protocol.model
import org.alephium.protocol.model.{Address, NetworkType, TxOutput}
import org.alephium.util.{AVector, TimeStamp, U256}

sealed trait Output {
  def amount: U256
  def address: Address
  def tokens: AVector[Token]
}

object Output {

  @upickle.implicits.key("asset")
  final case class Asset(
      amount: U256,
      address: Address,
      tokens: AVector[Token],
      lockTime: TimeStamp,
      additionalData: ByteString
  ) extends Output

  @upickle.implicits.key("contract")
  final case class Contract(
      amount: U256,
      address: Address,
      tokens: AVector[Token]
  ) extends Output

  def from(output: TxOutput, networkType: NetworkType): Output = {
    output match {
      case o: model.AssetOutput =>
        Asset(
          o.amount,
          Address.Asset(networkType, o.lockupScript),
          o.tokens.map((Token.apply).tupled),
          o.lockTime,
          o.additionalData
        )
      case o: model.ContractOutput =>
        Contract(
          o.amount,
          Address.Contract(networkType, o.lockupScript),
          o.tokens.map((Token.apply).tupled)
        )
    }
  }
}
