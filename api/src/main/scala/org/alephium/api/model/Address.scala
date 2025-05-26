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

import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.{LockupScript, PublicKeyLike}
import org.alephium.util.Base58

final case class Address(lockupScript: Address.DecodedLockupScript) extends AnyVal

object Address {
  def from(lockupScript: LockupScript): Address =
    Address(CompleteLockupScript(lockupScript))

  def fromBase58(input: String): Either[String, Address] = {
    if (LockupScript.Groupless.hasExplicitGroupIndex(input)) {
      LockupScript.decodeGroupless(input).map(from).toRight(s"Invalid groupless address $input")
    } else {
      Base58.decode(input) match {
        case Some(bytes) =>
          if (bytes.startsWith(LockupScript.P2PKPrefix)) {
            halfDecodeP2PK(bytes).toRight(s"Invalid p2pk address $input")
          } else if (bytes.startsWith(LockupScript.P2HMPKPrefix)) {
            halfDecodeP2HMPK(bytes).toRight(s"Invalid p2hmpk address $input")
          } else {
            LockupScript.decodeLockupScript(bytes).map(from).toRight(s"Invalid address $input")
          }
        case None => Left(s"Invalid address $input")
      }
    }
  }

  private def halfDecodeP2PK(bytes: ByteString): Option[Address] = {
    LockupScript.P2PK.decodePublicKey(bytes.drop(1)).map(v => Address(HalfDecodedP2PK(v)))
  }

  private def halfDecodeP2HMPK(bytes: ByteString): Option[Address] = {
    LockupScript.P2HMPK.decodeHash(bytes.drop(1)).map(v => Address(HalfDecodedP2HMPK(v)))
  }

  trait DecodedLockupScript

  final case class CompleteLockupScript(lockupScript: LockupScript) extends DecodedLockupScript
  sealed trait HalfDecodedLockupScript extends DecodedLockupScript {
    def getLockupScript(implicit config: GroupConfig): LockupScript
  }

  final case class HalfDecodedP2PK(publicKey: PublicKeyLike) extends HalfDecodedLockupScript {
    def getLockupScript(implicit config: GroupConfig): LockupScript =
      LockupScript.P2PK(publicKey)
  }

  final case class HalfDecodedP2HMPK(hash: Hash) extends HalfDecodedLockupScript {
    def getLockupScript(implicit config: GroupConfig): LockupScript =
      LockupScript.P2HMPK(hash)
  }
}
