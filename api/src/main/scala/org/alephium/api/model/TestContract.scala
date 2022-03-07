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
import org.alephium.api.model.TestContract._
import org.alephium.protocol.{vm, ALPH, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AssetOutput, ContractId, ContractOutput, GroupIndex}
import org.alephium.protocol.vm.{Val => _, _}
import org.alephium.util.{AVector, TimeStamp, U256}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class TestContract(
    group: Option[Int] = None,
    contractId: Option[ContractId] = None,
    bytecode: StatefulContract,
    initialFields: Option[AVector[Val]] = None,
    initialAsset: Option[TestContract.Asset] = None,
    testMethodIndex: Option[Int] = None,
    testArgs: Option[AVector[Val]] = None,
    existingContracts: Option[AVector[TestContract.ContractState]] = None,
    inputAssets: Option[AVector[TestContract.InputAsset]] = None
) {
  def toComplete: TestContract.Complete =
    Complete(
      group.getOrElse(groupDefault),
      contractId.getOrElse(contractIdDefault),
      code = bytecode,
      initialFields.getOrElse(initialFieldsDefault),
      initialAsset.getOrElse(initialAssetDefault),
      testMethodIndex.getOrElse(testMethodIndexDefault),
      testArgs.getOrElse(testArgsDefault),
      existingContracts.getOrElse(existingContractsDefault),
      inputAssets.getOrElse(inputAssetsDefault)
    )
}

object TestContract {
  val groupDefault: Int                                = 0
  val contractIdDefault: ContractId                    = ContractId.zero
  val initialFieldsDefault: AVector[Val]               = AVector.empty
  val testMethodIndexDefault: Int                      = 0
  val testArgsDefault: AVector[Val]                    = AVector.empty
  val existingContractsDefault: AVector[ContractState] = AVector.empty
  val inputAssetsDefault: AVector[InputAsset]          = AVector.empty
  val initialAssetDefault: Asset                       = Asset(ALPH.alph(1))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class Complete(
      group: Int = groupDefault,
      contractId: ContractId = contractIdDefault,
      code: StatefulContract,
      initialFields: AVector[Val] = initialFieldsDefault,
      initialAsset: TestContract.Asset = initialAssetDefault,
      testMethodIndex: Int = testMethodIndexDefault,
      testArgs: AVector[Val] = testArgsDefault,
      existingContracts: AVector[TestContract.ContractState] = existingContractsDefault,
      inputAssets: AVector[TestContract.InputAsset] = inputAssetsDefault
  ) {
    def groupIndex(implicit groupConfig: GroupConfig): Try[GroupIndex] = {
      GroupIndex.from(group).toRight(badRequest("Invalid group index"))
    }
  }

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
  final case class ContractState(
      id: ContractId,
      code: StatefulContract,
      codeHash: Hash,
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
