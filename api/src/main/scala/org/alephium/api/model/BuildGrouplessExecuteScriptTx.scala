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
import org.alephium.protocol.model.{BlockHash, GroupIndex}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, StatefulScript, UnlockScript}
import org.alephium.serde.deserialize
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BuildGrouplessExecuteScriptTx(
    fromAddress: String,
    bytecode: ByteString,
    attoAlphAmount: Option[Amount] = None,
    tokens: Option[AVector[Token]] = None,
    gasPrice: Option[GasPrice] = None,
    targetBlockHash: Option[BlockHash] = None,
    gasEstimationMultiplier: Option[Double] = None
) extends BuildGrouplessTx
    with BuildTxCommon.ExecuteScriptTx {
  def gasAmount: Option[GasBox] = None

  def getLockPair()(implicit config: GroupConfig): Try[(LockupScript.Asset, UnlockScript)] =
    lockPair.left.map(badRequest)

  def groupIndex()(implicit config: GroupConfig): Try[GroupIndex] = {
    if (LockupScript.P2PK.hasExplicitGroupIndex(fromAddress)) {
      getFromAddress().map(_.groupIndex).left.map(badRequest)
    } else {
      deserialize[StatefulScript](bytecode).left.map(serdeError =>
        badRequest(serdeError.getMessage)
      ) match {
        case Right(script) =>
          StatefulScript.deriveContractAddress(script) match {
            case Some(contractAddress) =>
              Right(contractAddress.groupIndex)
            case None =>
              Left(
                badRequest(
                  "Can not determine group: `fromAddress` has no explicit group and no contract address can be derived from TxScript"
                )
              )
          }
        case Left(error) =>
          Left(error)
      }
    }
  }
}
