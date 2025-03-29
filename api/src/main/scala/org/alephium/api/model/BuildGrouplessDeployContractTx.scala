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

import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, AddressLike, BlockHash, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.protocol.vm.LockupScript.HalfDecodedP2PK
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BuildGrouplessDeployContractTx(
    fromAddress: AddressLike,
    bytecode: ByteString,
    initialAttoAlphAmount: Option[Amount] = None,
    initialTokenAmounts: Option[AVector[Token]] = None,
    issueTokenAmount: Option[Amount] = None,
    issueTokenTo: Option[Address.Asset] = None,
    gasPrice: Option[GasPrice] = None,
    targetBlockHash: Option[BlockHash] = None
) extends BuildGrouplessTx
    with BuildTxCommon.DeployContractTx {
  def gasAmount: Option[GasBox] = None

  private val explicitGroupInfoError = {
    s"Contract deployment requires groupless address `${fromAddress}` with explicit group information"
  }
  override def getLockPair()(implicit
      config: GroupConfig
  ): Either[String, (LockupScript.P2PK, UnlockScript)] = {
    fromAddress.lockupScriptResult match {
      case LockupScript.CompleteLockupScript(lockupScript) =>
        lockupScript match {
          case p2pkLockupScript: LockupScript.P2PK => Right((p2pkLockupScript, UnlockScript.P2PK))
          case _ => Left(notGrouplessAddressError)
        }
      case LockupScript.HalfDecodedP2PK(_) =>
        Left(explicitGroupInfoError)
    }
  }

  def groupIndex()(implicit config: GroupConfig): Either[String, GroupIndex] = {
    fromAddress.lockupScriptResult match {
      case LockupScript.CompleteLockupScript(lockupScript: LockupScript.P2PK) =>
        Right(lockupScript.groupIndex)
      case HalfDecodedP2PK(_) =>
        Left(explicitGroupInfoError)
      case _ =>
        Left(notGrouplessAddressError)
    }
  }
}
