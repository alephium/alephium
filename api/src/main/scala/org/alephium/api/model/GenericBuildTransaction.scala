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

import org.alephium.api._
import org.alephium.api.model.Val
import org.alephium.protocol._
import org.alephium.protocol.model.{Address, BlockHash}
import org.alephium.protocol.vm._
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class GenericBuildTransaction(
    inputs: AVector[BuildInputs],
    destinations: AVector[Destination],
    gasAmount: GasBox,
    gasPrice: GasPrice,
    targetBlockHash: Option[BlockHash] = None
) extends GasInfo

sealed trait BuildInputs {
  def utxos: AVector[OutputRef]
  def lockupScript: LockupScript.Asset
  def unlockScriptTry: Try[UnlockScript]
}

@upickle.implicits.key("P2PKHInputs")
final case class P2PKHInputs(fromPublicKey: PublicKey, utxos: AVector[OutputRef])
    extends BuildInputs {
  val lockupScript    = LockupScript.p2pkh(fromPublicKey)
  val unlockScriptTry = Right(UnlockScript.p2pkh(fromPublicKey))
}

@upickle.implicits.key("P2MPKHInputs")
final case class P2MPKHInputs(
    fromAddress: Address.Asset,
    fromPublicKeys: AVector[PublicKey],
    utxos: AVector[OutputRef]
) extends BuildInputs {
  val lockupScript    = fromAddress.lockupScript
  val unlockScriptTry = ApiUtils.buildUnlockScript(fromAddress.lockupScript, fromPublicKeys)
}

@upickle.implicits.key("P2SHInputs")
final case class P2SHInputs(
    script: StatelessScript,
    params: AVector[Val],
    utxos: AVector[OutputRef]
) extends BuildInputs {
  val lockupScript    = LockupScript.p2sh(script)
  val unlockScriptTry = Right(UnlockScript.p2sh(script, Val.toVmVal(params)))
}
