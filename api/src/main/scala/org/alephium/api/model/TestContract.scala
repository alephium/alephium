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
import org.alephium.protocol.{vm, ALPH}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AssetOutput, ContractId, GroupIndex}
import org.alephium.protocol.vm.{ContractState => _, Val => _, _}
import org.alephium.util.{AVector, TimeStamp, U256}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class TestContract(
    group: Option[Int] = None,
    address: Option[Address.Contract] = None,
    bytecode: StatefulContract,
    initialFields: Option[AVector[Val]] = None,
    initialAsset: Option[ContractState.Asset] = None,
    testMethodIndex: Option[Int] = None,
    testArgs: Option[AVector[Val]] = None,
    existingContracts: Option[AVector[ContractState]] = None,
    inputAssets: Option[AVector[TestContract.InputAsset]] = None
) {
  def toComplete: TestContract.Complete =
    Complete(
      group.getOrElse(groupDefault),
      address.getOrElse(addressDefault).contractId,
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
  val addressDefault: Address.Contract                 = Address.contract(ContractId.zero)
  val initialFieldsDefault: AVector[Val]               = AVector.empty
  val testMethodIndexDefault: Int                      = 0
  val testArgsDefault: AVector[Val]                    = AVector.empty
  val existingContractsDefault: AVector[ContractState] = AVector.empty
  val inputAssetsDefault: AVector[InputAsset]          = AVector.empty
  val initialAssetDefault: ContractState.Asset         = ContractState.Asset(ALPH.alph(1))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class Complete(
      group: Int = groupDefault,
      contractId: ContractId = addressDefault.contractId,
      code: StatefulContract,
      initialFields: AVector[Val] = initialFieldsDefault,
      initialAsset: ContractState.Asset = initialAssetDefault,
      testMethodIndex: Int = testMethodIndexDefault,
      testArgs: AVector[Val] = testArgsDefault,
      existingContracts: AVector[ContractState] = existingContractsDefault,
      inputAssets: AVector[TestContract.InputAsset] = inputAssetsDefault
  ) {
    def groupIndex(implicit groupConfig: GroupConfig): Try[GroupIndex] = {
      GroupIndex.from(group).toRight(badRequest("Invalid group index"))
    }
  }

  final case class InputAsset(address: Address.Asset, asset: ContractState.Asset) {
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
