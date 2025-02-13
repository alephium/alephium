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
import org.alephium.protocol.model.{Address, BlockHash, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BuildGrouplessDeployContractTx(
    fromAddress: String,
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
  def getLockPair()(implicit config: GroupConfig): Try[(LockupScript.Asset, UnlockScript)] =
    lockPair.left.map(badRequest)

  def groupIndex()(implicit config: GroupConfig): Try[GroupIndex] = {
    if (LockupScript.P2PK.hasExplicitGroupIndex(fromAddress)) {
      getFromAddress().map(_.groupIndex).left.map(badRequest)
    } else {
      Left(
        badRequest(
          "Contract deployment requires a groupless address with explicit group information"
        )
      )
    }
  }
}
