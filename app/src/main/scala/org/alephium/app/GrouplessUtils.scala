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

package org.alephium.app

import org.alephium.api.{badRequest, failed, wrapResult, Try}
import org.alephium.api.model._
import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.model
import org.alephium.protocol.model.{Balance => _, _}
import org.alephium.protocol.vm.{LockupScript, UnlockScript}
import org.alephium.util.AVector

trait GrouplessUtils extends ChainedTxUtils { self: ServerUtils =>

  def buildGrouplessTransferTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessTransferTx
  ): Try[AVector[BuildTransferTxResult]] = {
    for {
      lockPair <- query.getLockPair().left.map(badRequest)
      result <- buildTransferTxWithFallbackAddresses(
        blockFlow,
        lockPair,
        otherGroupsLockupPairs(lockPair._1),
        query.destinations,
        query.gasPrice,
        query.targetBlockHash
      )
    } yield result
  }

  def buildGrouplessExecuteScriptTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessExecuteScriptTx
  ): Try[BuildGrouplessExecuteScriptTxResult] = {
    for {
      lockPair <- query.getLockPair().left.map(badRequest)
      amounts  <- query.getAmounts.left.map(badRequest)
      script   <- query.decodeStatefulScript().left.map(badRequest)
      result <- buildExecuteScriptTxWithFallbackAddresses(
        blockFlow,
        lockPair,
        otherGroupsLockupPairs(lockPair._1),
        script,
        amounts,
        query.gasEstimationMultiplier,
        query.gasAmount,
        query.gasPrice,
        query.targetBlockHash
      )
    } yield result
  }

  def buildGrouplessDeployContractTx(
      blockFlow: BlockFlow,
      query: BuildGrouplessDeployContractTx
  ): Try[BuildGrouplessDeployContractTxResult] = {
    for {
      lockPair <- query.getLockPair().left.map(badRequest)
      result <- buildDeployContractTxWithFallbackAddresses(
        blockFlow,
        lockPair,
        otherGroupsLockupPairs(lockPair._1),
        query,
        query.gasAmount,
        query.gasPrice,
        query.targetBlockHash
      )
    } yield result
  }

  def getGrouplessBalance(
      blockFlow: BlockFlow,
      halfDecodedP2PK: LockupScript.HalfDecodedP2PK,
      getMempoolUtxos: Boolean
  ): Try[Balance] = {
    for {
      allBalances <- wrapResult(AVector.from(brokerConfig.groupRange).mapE { groupIndex =>
        val lockupScript =
          LockupScript.p2pk(halfDecodedP2PK.publicKey, GroupIndex.unsafe(groupIndex))
        blockFlow.getBalance(lockupScript, apiConfig.defaultUtxosLimit, getMempoolUtxos)
      })
      balance <- allBalances.foldE(model.Balance.zero)(_ merge _).left.map(failed)
    } yield Balance.from(balance)
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def otherGroupsLockupPairs(
      lockup: LockupScript.P2PK
  ): AVector[(LockupScript.Asset, UnlockScript)] = {
    AVector
      .from(self.brokerConfig.groupRange)
      .filter(groupIndex => groupIndex != lockup.groupIndex.value)
      .map(groupIndex =>
        (
          LockupScript
            .P2PK(lockup.publicKey, GroupIndex.unsafe(groupIndex))
            .asInstanceOf[LockupScript.Asset],
          UnlockScript.P2PK.asInstanceOf[UnlockScript]
        )
      )
  }
}
