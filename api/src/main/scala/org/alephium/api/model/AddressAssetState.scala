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

import org.alephium.protocol.model
import org.alephium.protocol.model.Address
import org.alephium.util.{AVector, U256}
final case class AddressAssetState(address: Address, attoAlphAmount: U256, tokens: Option[AVector[Token]])

object AddressAssetState {
  def from(output: model.TxOutput): AddressAssetState = {
    AddressAssetState(
      Address.from(output.lockupScript),
      output.amount,
      Some(output.tokens.map(pair => Token(pair._1, pair._2)))
    )
  }
}

