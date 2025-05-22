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

package org.alephium.protocol.vm

import org.alephium.io.IOResult
import org.alephium.protocol.model._
import org.alephium.protocol.vm.nodeindexes._
import org.alephium.util.{AVector, U256}

object ContractRunner {
  def createContractUnsafe(
      worldState: WorldState.Staging,
      contractId: ContractId,
      code: StatefulContract,
      initialImmState: AVector[Val],
      initialMutState: AVector[Val],
      asset: ContractOutput,
      blockHash: BlockHash,
      txId: TransactionId
  ): IOResult[Unit] = {
    val outputRef = contractId.inaccurateFirstOutputRef()
    worldState.createContractLemanUnsafe(
      contractId,
      code.toHalfDecoded(),
      initialImmState,
      initialMutState,
      outputRef,
      asset,
      txId,
      Some(TxOutputLocator(blockHash, 0, 0))
    )
  }

  private def approveAsset(
      asset: AssetOutput,
      gasFeeOpt: Option[U256]
  ): AVector[Instr[StatefulContext]] = {
    val addressConst = AddressConst(Val.Address(asset.lockupScript))
    val attoAlphAmount = gasFeeOpt match {
      case Some(gasFee) => asset.amount.subUnsafe(gasFee)
      case None         => asset.amount
    }
    val alphInstrs = AVector[Instr[StatefulContext]](
      addressConst,
      U256Const(Val.U256(attoAlphAmount)),
      ApproveAlph
    )
    val tokenInstrs = asset.tokens.flatMap[Instr[StatefulContext]] { token =>
      AVector(
        addressConst,
        BytesConst(Val.ByteVec(token._1.bytes)),
        U256Const(Val.U256(token._2)),
        ApproveToken
      )
    }
    alphInstrs ++ tokenInstrs
  }

  private def approveAsset(
      assets: AVector[AssetOutput],
      gasFee: U256
  ): AVector[Instr[StatefulContext]] = {
    assets.flatMapWithIndex { (asset, index) =>
      val gasFeeOpt = if (index == 0) Some(gasFee) else None
      approveAsset(asset, gasFeeOpt)
    }
  }

  private def callExternal(
      args: AVector[Val],
      methodIndex: Int,
      argLength: Int,
      returnLength: Int,
      contractId: ContractId
  ): AVector[Instr[StatefulContext]] = {
    args.map(_.toConstInstr: Instr[StatefulContext]) ++
      AVector[Instr[StatefulContext]](
        ConstInstr.u256(Val.U256(U256.unsafe(argLength))),
        ConstInstr.u256(Val.U256(U256.unsafe(returnLength))),
        BytesConst(Val.ByteVec(contractId.bytes)),
        CallExternal(methodIndex.toByte)
      )
  }

  // scalastyle:off method.length parameter.number
  def run(
      context: StatefulContext,
      contractId: ContractId,
      callerContractIdOpt: Option[ContractId],
      inputAssets: AVector[AssetOutput],
      methodIndex: Int,
      args: AVector[Val],
      method: Method[StatefulContext],
      testGasFee: U256
  ): ExeResult[(AVector[Val], StatefulVM.TxScriptExecution)] = {
    callerContractIdOpt match {
      case None =>
        val script = StatefulScript.unsafe(
          AVector(
            Method[StatefulContext](
              isPublic = true,
              usePreapprovedAssets = inputAssets.nonEmpty,
              useContractAssets = false,
              usePayToContractOnly = false,
              useRoutePattern = false,
              argsLength = 0,
              localsLength = 0,
              returnLength = method.returnLength,
              instrs = approveAsset(inputAssets, testGasFee) ++ callExternal(
                args,
                methodIndex,
                method.argsLength,
                method.returnLength,
                contractId
              )
            )
          )
        )
        StatefulVM.runTxScriptWithOutputsTestOnly(context, script)
      case Some(callerContractId) =>
        val mockCallerContract = StatefulContract(
          0,
          AVector(
            Method[StatefulContext](
              isPublic = true,
              usePreapprovedAssets = inputAssets.nonEmpty,
              useContractAssets = false,
              usePayToContractOnly = false,
              useRoutePattern = false,
              argsLength = 0,
              localsLength = 0,
              returnLength = method.returnLength,
              instrs = approveAsset(inputAssets, testGasFee) ++ callExternal(
                args,
                methodIndex,
                method.argsLength,
                method.returnLength,
                contractId
              )
            )
          )
        )
        StatefulVM.runCallerContractWithOutputsTestOnly(
          context,
          mockCallerContract,
          callerContractId
        )
    }
  }
  // scalastyle:on method.length parameter.number
}
