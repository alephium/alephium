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

package org.alephium.protocol.model

import org.alephium.crypto.BIP340SchnorrPublicKey
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, StatelessScript, UnlockScript}
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util.{AVector, Base58}
import org.alephium.util.Hex.HexStringSyntax

sealed trait Address {
  def lockupScript: LockupScript

  def groupIndex(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex

  def toBase58: String = Base58.encode(serialize(lockupScript))

  override def toString: String = toBase58
}

object Address {
  final case class Asset(lockupScript: LockupScript.Asset) extends Address
  final case class Contract(lockupScript: LockupScript.P2C) extends Address {
    def contractId: ContractId = lockupScript.contractId
  }

  def from(lockupScript: LockupScript): Address = {
    lockupScript match {
      case e: LockupScript.Asset => Asset(e)
      case e: LockupScript.P2C   => Contract(e)
    }
  }

  def contract(contractId: ContractId): Address.Contract = {
    Contract(LockupScript.p2c(contractId))
  }

  def fromBase58(input: String): Option[Address] = {
    for {
      lockupScript <- LockupScript.fromBase58(input)
    } yield from(lockupScript)
  }

  def asset(input: String): Option[Address.Asset] = {
    fromBase58(input) match {
      case Some(address: Asset) => Some(address)
      case _                    => None
    }
  }

  def extractLockupScript(address: String): Option[LockupScript] = {
    for {
      lockupScript <- LockupScript.fromBase58(address)
    } yield lockupScript
  }

  def p2pkh(publicKey: PublicKey): Address.Asset =
    Asset(LockupScript.p2pkh(publicKey))

  lazy val schnorrAddressLockupScript: String =
    s"""
       |AssetScript Schnorr(publicKey: ByteVec) {
       |  pub fn unlock() -> () {
       |    verifyBIP340Schnorr!(txId!(), publicKey, getSegregatedSignature!())
       |  }
       |}
       |""".stripMargin
}

final case class SchnorrAddress(publicKey: BIP340SchnorrPublicKey) {
  // bytecode template generated from the script is 0101000000000458{0}8685
  lazy val scriptByteCode = {
    hex"0101000000000458144020" ++ publicKey.bytes ++ hex"8685"
  }

  lazy val lockupScript: LockupScript.Asset = {
    LockupScript.p2sh(Hash.hash(scriptByteCode))
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  lazy val unlockScript: UnlockScript = {
    UnlockScript.p2sh(deserialize[StatelessScript](scriptByteCode).toOption.get, AVector.empty)
  }

  lazy val address: Address.Asset = Address.Asset(lockupScript)
}
