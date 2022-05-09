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
    artifactId: ArtifactId,
    initialFields: AVector[Val] = TestContract.initialFieldsDefault,
    initialAsset: Option[AssetState] = None,
    testMethodIndex: Option[Int] = None,
    testArgs: AVector[Val] = TestContract.testArgsDefault,
    existingContracts: Option[AVector[ContractState]] = None,
    inputAssets: Option[AVector[TestContract.InputAsset]] = None
) {
  def toComplete(): Try[TestContract.Complete] = {
    val methodIndex = testMethodIndex.getOrElse(testMethodIndexDefault)
    bytecode.methods.get(methodIndex) match {
      case Some(method) =>
        val testCode =
          if (method.isPublic) {
            bytecode
          } else {
            bytecode.copy(methods =
              bytecode.methods.replace(methodIndex, method.copy(isPublic = true))
            )
          }
        Right(
          Complete(
            group.getOrElse(groupDefault),
            address.getOrElse(addressDefault).contractId,
            artifactId = artifactId,
            code = testCode,
            initialFields,
            initialAsset.getOrElse(initialAssetDefault),
            methodIndex,
            testArgs,
            existingContracts.getOrElse(existingContractsDefault),
            inputAssets.getOrElse(inputAssetsDefault)
          )
        )
      case None => Left(badRequest(s"Invalid method index ${methodIndex}"))
    }
  }
}

object TestContract {
  val groupDefault: Int                                = 0
  val addressDefault: Address.Contract                 = Address.contract(ContractId.zero)
  val initialFieldsDefault: AVector[Val]               = AVector.empty
  val testMethodIndexDefault: Int                      = 0
  val testArgsDefault: AVector[Val]                    = AVector.empty
  val existingContractsDefault: AVector[ContractState] = AVector.empty
  val inputAssetsDefault: AVector[InputAsset]          = AVector.empty
  val initialAssetDefault: AssetState                  = AssetState(ALPH.alph(1))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class Complete(
      group: Int = groupDefault,
      contractId: ContractId = addressDefault.contractId,
      artifactId: ArtifactId,
      code: StatefulContract,
      initialFields: AVector[Val] = initialFieldsDefault,
      initialAsset: AssetState = initialAssetDefault,
      testMethodIndex: Int = testMethodIndexDefault,
      testArgs: AVector[Val] = testArgsDefault,
      existingContracts: AVector[ContractState] = existingContractsDefault,
      inputAssets: AVector[TestContract.InputAsset] = inputAssetsDefault
  ) {
    def groupIndex(implicit groupConfig: GroupConfig): Try[GroupIndex] = {
      GroupIndex.from(group).toRight(badRequest("Invalid group index"))
    }
  }

  final case class InputAsset(address: Address.Asset, asset: AssetState) {
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
