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

import akka.util.ByteString

import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{ContractId, GroupIndex, Hint, ScriptHint}
import org.alephium.serde._
import org.alephium.util.{AVector, Base58, Bytes}

sealed trait LockupScript {
  def scriptHint: ScriptHint

  def hintBytes: ByteString

  def isAssetType: Boolean
}

object LockupScript {
  implicit val serde: Serde[LockupScript] = new Serde[LockupScript] {
    override def serialize(input: LockupScript): ByteString = {
      input match {
        case s: P2PKH  => ByteString(0) ++ serdeImpl[Hash].serialize(s.pkHash)
        case s: P2MPKH => ByteString(1) ++ P2MPKH.serde.serialize(s)
        case s: P2SH   => ByteString(2) ++ serdeImpl[Hash].serialize(s.scriptHash)
        case s: P2C    => ByteString(3) ++ serdeImpl[Hash].serialize(s.contractId.value)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[LockupScript]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(0, content) =>
          serdeImpl[Hash]._deserialize(content).map(_.mapValue(P2PKH))
        case Staging(1, content) =>
          P2MPKH.serde._deserialize(content)
        case Staging(2, content) =>
          serdeImpl[Hash]._deserialize(content).map(_.mapValue(P2SH))
        case Staging(3, content) =>
          P2C.serde._deserialize(content)
        case Staging(n, _) =>
          Left(SerdeError.wrongFormat(s"Invalid lockupScript prefix $n"))
      }
    }
  }

  val vmDefault: LockupScript = p2pkh(Hash.zero)

  def fromBase58(input: String): Option[LockupScript] = {
    Base58.decode(input).flatMap(deserialize[LockupScript](_).toOption)
  }

  def asset(input: String): Option[LockupScript.Asset] = {
    fromBase58(input).flatMap {
      case e: LockupScript.Asset => Some(e)
      case _                     => None
    }
  }

  def p2pkh(key: PublicKey): P2PKH = p2pkh(Hash.hash(key.bytes))
  def p2pkh(pkHash: Hash): P2PKH   = P2PKH(pkHash)
  def p2mpkh(keys: AVector[PublicKey], m: Int): Option[P2MPKH] = {
    Option.when(P2MPKH.validate(keys.length, m))(p2mpkhUnsafe(keys, m))
  }
  def p2mpkhUnsafe(keys: AVector[PublicKey], m: Int): P2MPKH = {
    P2MPKH.unsafe(keys.map(key => Hash.hash(key.bytes)), m)
  }
  def p2sh(script: StatelessScript): P2SH =
    P2SH(Hash.hash(serdeImpl[StatelessScript].serialize(script)))
  def p2sh(scriptHash: Hash): P2SH     = P2SH(scriptHash)
  def p2c(contractId: ContractId): P2C = P2C(contractId)
  def p2c(input: String): Option[LockupScript.P2C] = {
    fromBase58(input).flatMap {
      case e: LockupScript.P2C => Some(e)
      case _                   => None
    }
  }

  sealed trait Asset extends LockupScript {
    def hintBytes: ByteString = serialize(Hint.ofAsset(scriptHint))

    def groupIndex(implicit config: GroupConfig): GroupIndex = scriptHint.groupIndex

    def isAssetType: Boolean = true
  }
  object Asset {
    implicit val serde: Serde[Asset] = LockupScript.serde.xfmap[Asset](
      {
        case e: LockupScript.Asset => Right(e)
        case _ =>
          Left(SerdeError.validation(s"Expect LockupScript.Asset, but was LockupScript.P2C"))
      },
      identity
    )
  }

  // pay to public key hash
  final case class P2PKH(pkHash: Hash) extends Asset {
    lazy val scriptHint: ScriptHint = ScriptHint.fromHash(pkHash)
  }
  // pay to multi public key hash, i.e. m-of-n type multisig
  final case class P2MPKH private (pkHashes: AVector[Hash], m: Int) extends Asset {
    lazy val scriptHint: ScriptHint = ScriptHint.fromHash(pkHashes.head)
  }
  object P2MPKH {
    def validate(pkLength: Int, m: Int): Boolean = m > 0 && m <= pkLength

    implicit val serde: Serde[P2MPKH] = {
      val underlying: Serde[P2MPKH] = Serde.forProduct2(P2MPKH.apply, t => (t.pkHashes, t.m))
      underlying.validate(lock =>
        if (validate(lock.pkHashes.length, lock.m)) {
          Right(())
        } else {
          Left(s"Invalid m in m-of-n multisig: ${lock.pkHashes}, m: ${lock.m}")
        }
      )
    }

    def unsafe(pkHashes: AVector[Hash], m: Int): P2MPKH =
      new P2MPKH(pkHashes, m)
  }
  // pay to script hash
  final case class P2SH(scriptHash: Hash) extends Asset {
    lazy val scriptHint: ScriptHint = ScriptHint.fromHash(scriptHash)
  }
  // pay to contract (only used for contract outputs)
  final case class P2C(contractId: ContractId) extends LockupScript {
    lazy val scriptHint: ScriptHint = ScriptHint.fromHash(contractId.value)

    def hintBytes: ByteString = serialize(Hint.ofContract(scriptHint))

    def isAssetType: Boolean = false
  }
  object P2C {
    implicit val serde: Serde[P2C] = Serde.forProduct1(P2C.apply, t => t.contractId)
  }

  def groupIndex(shortKey: Int)(implicit config: GroupConfig): GroupIndex = {
    val hash = Bytes.toPosInt(Bytes.xorByte(shortKey))
    GroupIndex.unsafe(hash % config.groups)
  }
}
