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

import org.alephium.protocol.{Checksum, Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{ContractId, GroupIndex, Hint, ScriptHint}
import org.alephium.serde._
import org.alephium.util.{AVector, Base58, Bytes, DjbHash}

sealed trait LockupScript {
  def scriptHint: ScriptHint

  def groupIndex(implicit config: GroupConfig): GroupIndex

  def hintBytes: ByteString

  def isAssetType: Boolean
}

// scalastyle:off number.of.methods
object LockupScript {
  implicit val serde: Serde[LockupScript] = new Serde[LockupScript] {
    override def serialize(input: LockupScript): ByteString = {
      input match {
        case s: P2PKH  => ByteString(0) ++ serdeImpl[Hash].serialize(s.pkHash)
        case s: P2MPKH => ByteString(1) ++ P2MPKH.serde.serialize(s)
        case s: P2SH   => ByteString(2) ++ serdeImpl[Hash].serialize(s.scriptHash)
        case s: P2C    => ByteString(3) ++ serdeImpl[Hash].serialize(s.contractId.value)
        case s: P2PK   => ByteString(4) ++ P2PK.serde.serialize(s)
      }
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[LockupScript]] = {
      byteSerde._deserialize(input).flatMap {
        case Staging(0, content) =>
          serdeImpl[Hash]._deserialize(content).map(_.mapValue(P2PKH.apply))
        case Staging(1, content) =>
          P2MPKH.serde._deserialize(content)
        case Staging(2, content) =>
          serdeImpl[Hash]._deserialize(content).map(_.mapValue(P2SH.apply))
        case Staging(3, content) =>
          P2C.serde._deserialize(content)
        case Staging(4, content) =>
          P2PK.serde._deserialize(content)
        case Staging(n, _) =>
          Left(SerdeError.wrongFormat(s"Invalid lockupScript prefix $n"))
      }
    }
  }

  val vmDefault: LockupScript = p2pkh(Hash.zero)

  def fromBase58(input: String): Option[LockupScript] = {
    fromBase58(input, None)
  }

  def fromBase58(input: String, groupIndex: Option[GroupIndex]): Option[LockupScript] = {
    decodeFromBase58(input) match {
      case CompleteLockupScript(lockupScript) =>
        Some(lockupScript)
      case HalfDecodedP2PK(publicKey) =>
        groupIndex.map(groupIndex => p2pk(publicKey, groupIndex))
      case InvalidLockupScript =>
        None
    }
  }

  sealed trait DecodeLockupScriptResult {
    def getLockupScript: Option[LockupScript]
  }

  sealed trait ValidLockupScript extends DecodeLockupScriptResult

  final case class CompleteLockupScript(lockupScript: LockupScript) extends ValidLockupScript {
    def getLockupScript: Option[LockupScript] = Some(lockupScript)
  }
  case object InvalidLockupScript extends DecodeLockupScriptResult {
    def getLockupScript: Option[LockupScript] = None
  }
  final case class HalfDecodedP2PK(publicKey: PublicKeyLike) extends ValidLockupScript {
    def getLockupScript: Option[LockupScript] = None
  }

  def decodeFromBase58(input: String): DecodeLockupScriptResult = {
    if (P2PK.hasExplicitGroupIndex(input)) {
      decodeP2PK(input)
    } else {
      Base58
        .decode(input)
        .map { bytes =>
          if (bytes.startsWith(ByteString(4))) {
            halfDecodeP2PK(bytes)
          } else {
            decodeLockupScript(bytes)
          }
        }
        .getOrElse(InvalidLockupScript)
    }
  }

  private def halfDecodeP2PK(bytes: ByteString): DecodeLockupScriptResult = {
    P2PK.decodePublicKey(bytes.drop(1)) match {
      case Some(publicKey) => HalfDecodedP2PK(publicKey)
      case None            => InvalidLockupScript
    }
  }

  private def decodeLockupScript(bytes: ByteString): DecodeLockupScriptResult = {
    deserialize[LockupScript](bytes).toOption match {
      case Some(lockupScript) => CompleteLockupScript(lockupScript)
      case None               => InvalidLockupScript
    }
  }

  private def decodeP2PK(input: String): DecodeLockupScriptResult = {
    val result = for {
      groupByte <- input.takeRight(1).toByteOption
      bytes     <- Base58.decode(input.dropRight(2))
      lockupScriptOpt <-
        if (bytes.startsWith(ByteString(4))) {
          P2PK.fromDecodedBase58(bytes.drop(1), groupByte)
        } else {
          None
        }
    } yield lockupScriptOpt
    result match {
      case Some(lockupScript) => CompleteLockupScript(lockupScript)
      case None               => InvalidLockupScript
    }
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
  def p2pk(key: PublicKeyLike, groupIndex: GroupIndex): P2PK = P2PK(key, groupIndex)

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

    def groupIndex(implicit config: GroupConfig): GroupIndex = contractId.groupIndex

    def isAssetType: Boolean = false
  }
  object P2C {
    implicit val serde: Serde[P2C] = Serde.forProduct1(P2C.apply, t => t.contractId)
  }

  final case class P2PK private (publicKey: PublicKeyLike, groupByte: Byte) extends Asset {
    // We need to use `scriptHint` to calculate the group index in the `AssetOutputRef`,
    // so we need to find a `scriptHint` that matches the group index.
    // Since the least significant byte is already used to distinguish the output type,
    // we use the most significant byte here to calculate the new `scriptHint`.
    override lazy val scriptHint: ScriptHint = {
      val initialHint  = ScriptHint.fromHash(DjbHash.intHash(publicKey.rawBytes)).value
      val xorResult    = Bytes.xorByte(initialHint)
      val byte0        = (initialHint >> 24).toByte
      val newByte0     = byte0 ^ xorResult ^ groupByte
      val newHintValue = (newByte0 << 24) | (initialHint & 0x00ffffff)
      ScriptHint.fromHash(newHintValue)
    }

    override def groupIndex(implicit config: GroupConfig): GroupIndex =
      GroupIndex.unsafe(groupByte % config.groups)

    def toBase58: String = s"${toBase58WithoutGroup}:$groupByte"

    def toBase58WithoutGroup: String = {
      val bytes = serialize[LockupScript](this).dropRight(P2PK.groupByteLength)
      Base58.encode(bytes)
    }
  }

  object P2PK {
    private val groupByteLength: Int = 1

    def apply(publicKey: PublicKeyLike, groupIndex: GroupIndex): P2PK = {
      P2PK(publicKey, groupIndex.value.toByte)
    }

    def unsafe(publicKey: PublicKeyLike, groupByte: Byte): P2PK = {
      P2PK(publicKey, groupByte)
    }

    def hasExplicitGroupIndex(input: String): Boolean = {
      input.length > 2 && input(input.length - 2) == ':'
    }

    def decodePublicKey(bytes: ByteString): Option[PublicKeyLike] = {
      safePublicKeySerde.deserialize(bytes).toOption
    }

    def fromDecodedBase58(bytes: ByteString, groupByte: Byte): Option[P2PK] = {
      decodePublicKey(bytes).map(P2PK(_, groupByte))
    }

    private val safePublicKeySerde: Serde[PublicKeyLike] = new Serde[PublicKeyLike] {
      override def serialize(input: PublicKeyLike): ByteString = {
        val publicKey = PublicKeyLike.serde.serialize(input)
        val checksum  = Checksum.calcAndSerialize(publicKey)
        publicKey ++ checksum
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[PublicKeyLike]] = {
        for {
          publicKeyResult <- PublicKeyLike.serde._deserialize(input)
          data = input.take(input.length - publicKeyResult.rest.length)
          checksumResult <- Checksum.serde._deserialize(publicKeyResult.rest)
          _              <- checksumResult.value.check(data)
        } yield Staging(publicKeyResult.value, checksumResult.rest)
      }
    }

    implicit val serde: Serde[P2PK] = {
      Serde.forProduct2(P2PK.unsafe, (p: P2PK) => (p.publicKey, p.groupByte))(
        safePublicKeySerde,
        serdeImpl[Byte]
      )
    }
  }
}
// scalastyle:on number.of.methods
