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
import org.alephium.protocol.model.{AssetOutput, ContractOutput, TxOutput, TxOutputRef}
import org.alephium.util.{AVector, TimeStamp}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class UTXO(
    ref: OutputRef,
    amount: Amount,
    tokens: Option[AVector[Token]] = None,
    lockTime: Option[TimeStamp] = None,
    additionalData: Option[ByteString] = None
)

object UTXO {
  def from(ref: TxOutputRef, output: TxOutput): UTXO = {
    val tokens = if (output.tokens.isEmpty) None else Some(output.tokens.map(Token.tupled))
    val (lockTime, additionalData) = output match {
      case o: AssetOutput    => Some(o.lockTime) -> Some(o.additionalData)
      case _: ContractOutput => None             -> None
    }

    UTXO(
      OutputRef.from(ref),
      Amount(output.amount),
      tokens,
      lockTime,
      additionalData
    )
  }

  def from(
      ref: OutputRef,
      amount: Amount,
      tokens: AVector[Token],
      lockTime: TimeStamp,
      addtionalData: ByteString
  ): UTXO = {
    UTXO(ref, amount, Some(tokens), Some(lockTime), Some(addtionalData))
  }
}
