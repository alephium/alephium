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
import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.util.{AVector, TimeStamp, U256}

final case class UTXO(
    ref: OutputRef,
    amount: U256,
    tokens: AVector[Token],
    lockTime: TimeStamp,
    additionalData: ByteString
)

object UTXO {
  def from(outputInfo: AssetOutputInfo): UTXO = {
    import outputInfo.output._

    UTXO(
      OutputRef.from(outputInfo.ref),
      amount,
      tokens.map((Token.apply).tupled),
      lockTime,
      additionalData
    )
  }
}
