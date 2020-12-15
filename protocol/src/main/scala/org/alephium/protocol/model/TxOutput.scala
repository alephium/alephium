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

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util.{AVector, TimeStamp, U256}

sealed trait TxOutput {
  def amount: U256
  def lockupScript: LockupScript
  def tokens: AVector[(TokenId, U256)]

  def hint: Hint

  def isAsset: Boolean

  def payGasUnsafe(fee: U256): TxOutput
}

object TxOutput {
  implicit val serde: Serde[TxOutput] = eitherSerde[AssetOutput, ContractOutput].xmap(
    {
      case Left(assetOutput)     => assetOutput
      case Right(contractOutput) => contractOutput
    }, {
      case output: AssetOutput    => Left(output)
      case output: ContractOutput => Right(output)
    }
  )

  def from(amount: U256, tokens: AVector[(TokenId, U256)], lockupScript: LockupScript): TxOutput = {
    lockupScript match {
      case _: LockupScript.P2C => ContractOutput(amount, lockupScript, tokens)
      case _                   => AssetOutput(amount, lockupScript, TimeStamp.zero, tokens, ByteString.empty)
    }
  }

  def asset(amount: U256, lockupScript: LockupScript): AssetOutput = {
    asset(amount, lockupScript, TimeStamp.zero)
  }

  def asset(amount: U256, lockupScript: LockupScript, lockTime: TimeStamp): AssetOutput = {
    AssetOutput(amount, lockupScript, lockTime, AVector.empty, ByteString.empty)
  }

  def asset(amount: U256,
            lockupScript: LockupScript,
            lockTimeOpt: Option[TimeStamp]): AssetOutput = {
    val lockTime = lockTimeOpt.getOrElse(TimeStamp.zero)
    asset(amount, lockupScript, lockTime)
  }

  def contract(amount: U256, lockupScript: LockupScript): ContractOutput = {
    ContractOutput(amount, lockupScript, AVector.empty)
  }

  def genesis(amount: U256, lockupScript: LockupScript): AssetOutput = {
    asset(amount, lockupScript)
  }

  // TODO: improve this when vm is mature
  def forSMT: TxOutput =
    ContractOutput(U256.One, LockupScript.p2pkh(Hash.zero), AVector.empty)
}

/**
  *
  * @param amount the number of ALF in the output
  * @param lockupScript guarding script for unspent output
  * @param tokens secondary tokens in the output
  * @param additionalData data payload for additional information
  */
final case class AssetOutput(amount: U256,
                             lockupScript: LockupScript, // TODO: exclude p2c script
                             lockTime: TimeStamp,
                             tokens: AVector[(TokenId, U256)],
                             additionalData: ByteString)
    extends TxOutput {
  def isAsset: Boolean = true

  def hint: Hint = Hint.from(this)

  def toGroup(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex

  def payGasUnsafe(fee: U256): AssetOutput =
    AssetOutput(amount.subUnsafe(fee), lockupScript, lockTime, tokens, additionalData)
}

object AssetOutput {
  private[model] implicit val tokenSerde: Serde[(TokenId, U256)] = Serde.tuple2[TokenId, U256]
  implicit val serde: Serde[AssetOutput] =
    Serde.forProduct5(AssetOutput.apply,
                      t => (t.amount, t.lockupScript, t.lockTime, t.tokens, t.additionalData))
}

final case class ContractOutput(amount: U256,
                                lockupScript: LockupScript,
                                tokens: AVector[(TokenId, U256)])
    extends TxOutput {
  def isAsset: Boolean = false

  def hint: Hint = Hint.from(this)

  def payGasUnsafe(fee: U256): ContractOutput =
    ContractOutput(amount.subUnsafe(fee), lockupScript, tokens)
}

object ContractOutput {
  import AssetOutput.tokenSerde
  implicit val serde: Serde[ContractOutput] =
    Serde.forProduct3(ContractOutput.apply, t => (t.amount, t.lockupScript, t.tokens))
}
