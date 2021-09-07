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

import org.alephium.api.model.Token
import org.alephium.protocol.model.{AssetOutput, AssetOutputRef}
import org.alephium.util.{AVector, TimeStamp}

final case class UTXO(
    ref: OutputRef,
    amount: Amount,
    tokens: AVector[Token],
    lockTime: TimeStamp,
    additionalData: ByteString
)

object UTXO {
  def from(ref: AssetOutputRef, output: AssetOutput): UTXO = {
    import output._

    UTXO(
      OutputRef.from(ref),
      Amount(amount),
      tokens.map((Token.from).tupled),
      lockTime,
      additionalData
    )
  }
}
