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

import org.alephium.api.{badRequest, Try}
import org.alephium.api.model.TestContract._
import org.alephium.protocol.{ALPH, BlockHash, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, ContractId, GroupIndex}
import org.alephium.protocol.vm.{ContractState => _, Val => _, _}
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class TestContract(
    group: Option[Int] = None,
    blockHash: Option[BlockHash] = None,
    txId: Option[Hash] = None,
    address: Option[Address.Contract] = None,
    bytecode: StatefulContract,
    initialFields: Option[AVector[Val]] = None,
    initialAsset: Option[AssetState] = None,
    methodIndex: Option[Int] = None,
    args: Option[AVector[Val]] = None,
    existingContracts: Option[AVector[ContractState]] = None,
    inputAssets: Option[AVector[TestInputAsset]] = None
) {
  def toComplete(): Try[TestContract.Complete] = {
    val testMethodIndex = methodIndex.getOrElse(testMethodIndexDefault)
    bytecode.methods.get(testMethodIndex) match {
      case Some(method) =>
        val testCode =
          if (method.isPublic) {
            bytecode
          } else {
            bytecode.copy(methods =
              bytecode.methods.replace(testMethodIndex, method.copy(isPublic = true))
            )
          }
        Right(
          Complete(
            group.getOrElse(groupDefault),
            blockHash.getOrElse(BlockHash.random),
            txId.getOrElse(Hash.random),
            address.getOrElse(addressDefault).contractId,
            code = testCode,
            originalCodeHash = bytecode.hash,
            initialFields.getOrElse(AVector.empty),
            initialAsset.getOrElse(initialAssetDefault),
            testMethodIndex,
            args.getOrElse(AVector.empty),
            existingContracts.getOrElse(existingContractsDefault),
            inputAssets.getOrElse(inputAssetsDefault)
          )
        )
      case None => Left(badRequest(s"Invalid method index ${testMethodIndex}"))
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
  val inputAssetsDefault: AVector[TestInputAsset]      = AVector.empty
  val initialAssetDefault: AssetState                  = AssetState(ALPH.alph(1))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  final case class Complete(
      group: Int = groupDefault,
      blockHash: BlockHash = BlockHash.random,
      txId: Hash = Hash.random,
      contractId: ContractId = addressDefault.contractId,
      code: StatefulContract,
      originalCodeHash: Hash,
      initialFields: AVector[Val] = initialFieldsDefault,
      initialAsset: AssetState = initialAssetDefault,
      testMethodIndex: Int = testMethodIndexDefault,
      testArgs: AVector[Val] = testArgsDefault,
      existingContracts: AVector[ContractState] = existingContractsDefault,
      inputAssets: AVector[TestInputAsset] = inputAssetsDefault
  ) {
    // We return original code hash when testing private methods
    // We return the new code hash when the test code is migrated
    def codeHash(hash: Hash): Hash = {
      val codeMigrated = hash != code.hash
      if (!codeMigrated) originalCodeHash else hash
    }

    def groupIndex(implicit groupConfig: GroupConfig): Try[GroupIndex] = {
      GroupIndex.from(group).toRight(badRequest("Invalid group index"))
    }
  }
}
