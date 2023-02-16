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
import org.alephium.crypto.BIP340SchnorrPublicKey
import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.{BlockHash, SchnorrAddress}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript, UnlockScript}
import org.alephium.util.{AVector, Hex}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class BuildTransaction(
    fromPublicKey: ByteString,
    fromPublicKeyType: Option[BuildTransaction.PublicKeyType] = None,
    destinations: AVector[Destination],
    utxos: Option[AVector[OutputRef]] = None,
    gasAmount: Option[GasBox] = None,
    gasPrice: Option[GasPrice] = None,
    targetBlockHash: Option[BlockHash] = None
) extends BuildTxCommon {
  def lockPair(): Try[(LockupScript.Asset, UnlockScript)] = fromPublicKeyType match {
    case Some(BuildTransaction.BIP340Schnorr) => BuildTransaction.schnorrLockPair(fromPublicKey)
    case _                                    => BuildTransaction.p2pkhLockPair(fromPublicKey)
  }
}

object BuildTransaction {
  sealed trait PublicKeyType
  object Default       extends PublicKeyType // SecP256K1
  object BIP340Schnorr extends PublicKeyType

  def p2pkhLockPair(fromPublicKey: ByteString): Try[(LockupScript.Asset, UnlockScript)] = {
    PublicKey.from(fromPublicKey) match {
      case Some(publicKey) =>
        Right(LockupScript.p2pkh(publicKey) -> UnlockScript.p2pkh(publicKey))
      case None =>
        Left(badRequest(s"Invalid SecP256K1 public key: ${Hex.toHexString(fromPublicKey)}"))
    }
  }

  def schnorrLockPair(fromPublicKey: ByteString): Try[(LockupScript.Asset, UnlockScript)] = {
    BIP340SchnorrPublicKey.from(fromPublicKey) match {
      case Some(publicKey) =>
        val address = SchnorrAddress(publicKey)
        Right(address.lockupScript -> address.unlockScript)
      case None =>
        Left(badRequest(s"Invalid SecP256K1 public key: ${Hex.toHexString(fromPublicKey)}"))
    }
  }
}
