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
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AssetOutput, ContractId, ContractOutput, GroupIndex}
import org.alephium.protocol.vm
import org.alephium.protocol.vm.{Val => _, _}
import org.alephium.util.{AVector, TimeStamp, U256}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class TestContract(
    group: Int = 0,
    testContractId: ContractId = ContractId.zero,
    testCode: StatefulContract,
    initialFields: AVector[Val] = AVector.empty,
    initialAsset: TestContract.Asset,
    testMethodIndex: Int = 0,
    testArgs: AVector[Val] = AVector.empty,
    existingContracts: AVector[TestContract.ExistingContract] = AVector.empty,
    inputAssets: AVector[TestContract.InputAsset] = AVector.empty
) {
  def groupIndex(implicit groupConfig: GroupConfig): Try[GroupIndex] = {
    GroupIndex.from(group).toRight(badRequest("Invalid group index"))
  }
}

object TestContract {
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class Asset(alphAmount: U256, tokens: AVector[Token] = AVector.empty) {
    def toContractOutput(contractId: ContractId): ContractOutput = {
      ContractOutput(
        alphAmount,
        LockupScript.p2c(contractId),
        tokens.map(token => (token.id, token.amount))
      )
    }
  }

  object Asset {
    def from(output: ContractOutput): Asset = {
      Asset(output.amount, output.tokens.map(pair => Token(pair._1, pair._2)))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class ExistingContract(
      contractId: ContractId,
      code: StatefulContract,
      fields: AVector[Val] = AVector.empty,
      asset: Asset
  )

  final case class InputAsset(address: Address.Asset, asset: Asset) {
    def toAssetOutput: AssetOutput =
      AssetOutput(
        asset.alphAmount,
        address.lockupScript,
        TimeStamp.zero,
        asset.tokens.map(token => (token.id, token.amount)),
        ByteString.empty
      )

    def approveAll(gasFeeOpt: Option[U256]): AVector[Instr[StatefulContext]] = {
      val addressConst = AddressConst(vm.Val.Address(address.lockupScript))
      val alphAmount = gasFeeOpt match {
        case Some(gasFee) => asset.alphAmount.subUnsafe(gasFee)
        case None         => asset.alphAmount
      }
      val alphInstrs = AVector[Instr[StatefulContext]](
        addressConst,
        U256Const(vm.Val.U256(alphAmount)),
        ApproveAlph
      )
      val tokenInstrs = asset.tokens.flatMap[Instr[StatefulContext]] { token =>
        AVector(
          addressConst,
          BytesConst(vm.Val.ByteVec(token.id.bytes)),
          U256Const(vm.Val.U256(token.amount)),
          ApproveToken
        )
      }
      alphInstrs ++ tokenInstrs
    }
  }
}
