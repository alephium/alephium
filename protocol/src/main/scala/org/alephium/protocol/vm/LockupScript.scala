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

import org.alephium.protocol.{ALPH, Checksum, Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{ContractId, GroupIndex, Hint, ScriptHint}
import org.alephium.serde._
import org.alephium.util.{AVector, Base58, Bytes, DjbHash}

sealed trait LockupScript {
  def scriptHint: ScriptHint

  def groupIndex(implicit config: GroupConfig): GroupIndex

  def hintBytes: ByteString

  def isAssetType: Boolean

  def toBase58: String = Base58.encode(serialize(this))
}

// scalastyle:off number.of.methods
object LockupScript {
  val P2PKPrefix: ByteString   = ByteString(4)
  val P2HMPKPrefix: ByteString = ByteString(5)

  implicit val serde: Serde[LockupScript] = new Serde[LockupScript] {
    override def serialize(input: LockupScript): ByteString = {
      input match {
        case s: P2PKH  => ByteString(0) ++ serdeImpl[Hash].serialize(s.pkHash)
        case s: P2MPKH => ByteString(1) ++ P2MPKH.serde.serialize(s)
        case s: P2SH   => ByteString(2) ++ serdeImpl[Hash].serialize(s.scriptHash)
        case s: P2C    => ByteString(3) ++ serdeImpl[Hash].serialize(s.contractId.value)
        case s: P2PK   => P2PKPrefix ++ P2PK.serde.serialize(s)
        case s: P2HMPK => P2HMPKPrefix ++ P2HMPK.serde.serialize(s)
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
        case Staging(5, content) =>
          P2HMPK.serde._deserialize(content)
        case Staging(n, _) =>
          Left(SerdeError.wrongFormat(s"Invalid lockupScript prefix $n"))
      }
    }
  }

  val vmDefault: LockupScript = p2pkh(Hash.zero)

  def fromBase58(input: String): Either[String, LockupScript] = {
    if (Groupless.hasExplicitGroupIndex(input)) {
      decodeGroupless(input).toRight(s"Invalid grouped address $input")
    } else {
      for {
        bytes <- Base58.decode(input).toRight(s"Invalid base58 string $input")
        lockupScript <- deserialize[LockupScript](bytes).left.map { error =>
          if (bytes.startsWith(P2PKPrefix) || bytes.startsWith(P2HMPKPrefix)) {
            s"Expected a grouped address, but got a groupless one: $input"
          } else {
            s"Invalid address $input: ${error.getMessage}"
          }
        }
      } yield lockupScript
    }
  }

  def decodeGroupless(input: String): Option[LockupScript] = {
    for {
      groupByte <- input.takeRight(1).toByteOption
      bytes     <- Base58.decode(input.dropRight(2))
      lockupScriptOpt <-
        if (bytes.startsWith(P2PKPrefix)) {
          P2PK.fromDecodedBase58(bytes.drop(1), groupByte)
        } else if (bytes.startsWith(P2HMPKPrefix)) {
          P2HMPK.fromDecodedBase58(bytes.drop(1), groupByte)
        } else {
          None
        }
    } yield lockupScriptOpt
  }

  def asset(input: String): Either[String, LockupScript.Asset] = {
    fromBase58(input) match {
      case Right(e: LockupScript.Asset) => Right(e)
      case Right(_: LockupScript.P2C) =>
        Left(s"Expected an asset address, but got a contract address: $input")
      case Left(error) => Left(error)
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
  def p2c(input: String): Either[String, LockupScript.P2C] = {
    fromBase58(input) match {
      case Right(e: LockupScript.P2C) => Right(e)
      case Right(_)    => Left(s"Expected a contract address, but got an asset address: $input")
      case Left(error) => Left(error)
    }
  }
  def p2pk(key: PublicKeyLike, groupIndex: GroupIndex): P2PK = P2PK(key, groupIndex)
  def p2hmpk(hash: Hash, groupIndex: GroupIndex): P2HMPK     = P2HMPK(hash, groupIndex.value.toByte)
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

  sealed trait GroupedAsset extends Asset with Product with Serializable {
    def groupByte: Byte

    override def groupIndex(implicit config: GroupConfig): GroupIndex =
      GroupIndex.unsafe(groupByte % config.groups)

    override def toBase58: String = {
      val bytes = serialize[LockupScript](this).dropRight(Groupless.groupByteLength)
      s"${Base58.encode(bytes)}:$groupByte"
    }
  }

  // pay to public key hash
  final case class P2PKH(pkHash: Hash) extends Asset {
    lazy val scriptHint: ScriptHint = ScriptHint.fromHash(pkHash)
  }
  // pay to multi public key hash, i.e. m-of-n type multisig
  final case class P2MPKH private (pkHashes: AVector[Hash], m: Int) extends Asset {
    lazy val scriptHint: ScriptHint = ScriptHint.fromHash(pkHashes.head)
  }

  object P2MPKH extends MultiSig {

    implicit val serde: Serde[P2MPKH] = {
      val underlying: Serde[P2MPKH] = Serde.forProduct2(P2MPKH.apply, t => (t.pkHashes, t.m))
      underlying.validate(lock =>
        if (validate(lock.pkHashes.length, lock.m)) {
          Right(())
        } else {
          Left(validationErrorMsg(lock.pkHashes.length, lock.m))
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

  final case class P2PK private (publicKey: PublicKeyLike, groupByte: Byte) extends GroupedAsset {
    // We need to use `scriptHint` to calculate the group index in the `AssetOutputRef`,
    // so we need to find a `scriptHint` that matches the group index.
    // Since the least significant byte is already used to distinguish the output type,
    // we use the most significant byte here to calculate the new `scriptHint`.
    override lazy val scriptHint: ScriptHint =
      Groupless.calcScriptHint(publicKey.rawBytes, groupByte)
  }

  object P2PK {
    private val safePublicKeySerde: Serde[PublicKeyLike] = Groupless.safeSerde(PublicKeyLike.serde)

    def apply(publicKey: PublicKeyLike, groupIndex: GroupIndex): P2PK = {
      P2PK(publicKey, groupIndex.value.toByte)
    }

    def apply(publicKey: PublicKeyLike)(implicit groupConfig: GroupConfig): P2PK = {
      P2PK(publicKey, publicKey.defaultGroup)
    }

    def unsafe(publicKey: PublicKeyLike, groupByte: Byte): P2PK = {
      P2PK(publicKey, groupByte)
    }

    def decodePublicKey(bytes: ByteString): Option[PublicKeyLike] = {
      safePublicKeySerde.deserialize(bytes).toOption
    }

    def fromDecodedBase58(bytes: ByteString, groupByte: Byte): Option[P2PK] = {
      decodePublicKey(bytes).map(P2PK(_, groupByte))
    }

    implicit val serde: Serde[P2PK] = {
      Serde.forProduct2(P2PK.unsafe, (p: P2PK) => (p.publicKey, p.groupByte))(
        safePublicKeySerde,
        serdeImpl[Byte]
      )
    }
  }

  final case class P2HMPK private (
      p2hmpkHash: Hash,
      groupByte: Byte
  ) extends GroupedAsset {
    override lazy val scriptHint: ScriptHint = Groupless.calcScriptHint(p2hmpkHash.bytes, groupByte)
  }

  object P2HMPK extends MultiSig {

    def apply(p2hmpkHash: Hash, groupIndex: GroupIndex): P2HMPK = {
      P2HMPK(p2hmpkHash, groupIndex.value.toByte)
    }

    def apply(p2hmpkHash: Hash)(implicit config: GroupConfig): P2HMPK = {
      P2HMPK(p2hmpkHash, defaultGroup(p2hmpkHash))
    }

    def unsafe(p2hmpkHash: Hash, groupByte: Byte): P2HMPK = {
      P2HMPK(p2hmpkHash, groupByte)
    }

    def defaultGroup(p2hmpkHash: Hash)(implicit config: GroupConfig): GroupIndex =
      Groupless.defaultGroup(p2hmpkHash.bytes)

    def apply(
        publicKeys: AVector[PublicKeyLike],
        m: Int,
        groupIndex: GroupIndex
    ): Either[String, P2HMPK] = {
      for {
        _ <- checkPublicKeysNumber(publicKeys)
        _ <- checkThreshold(publicKeys, m)
      } yield P2HMPK(calcHash(publicKeys, m), groupIndex.value.toByte)
    }

    def apply(publicKeys: AVector[PublicKeyLike], m: Int)(implicit
        config: GroupConfig
    ): Either[String, P2HMPK] = {
      for {
        _ <- checkPublicKeysNumber(publicKeys)
        _ <- checkThreshold(publicKeys, m)
      } yield {
        val hash = calcHash(publicKeys, m)
        P2HMPK(hash, defaultGroup(hash))
      }
    }

    private def checkThreshold(publicKeys: AVector[PublicKeyLike], m: Int): Either[String, Unit] = {
      if (P2HMPK.validate(publicKeys.length, m)) {
        Right(())
      } else {
        Left(validationErrorMsg(publicKeys.length, m))
      }
    }

    private def checkPublicKeysNumber(publicKeys: AVector[PublicKeyLike]): Either[String, Unit] = {
      if (publicKeys.length <= ALPH.MaxKeysInP2HMPK) {
        Right(())
      } else {
        Left(
          s"Too many public keys in P2HMPK: ${publicKeys.length}, max is ${ALPH.MaxKeysInP2HMPK}"
        )
      }
    }

    def unsafe(publicKeys: AVector[PublicKeyLike], m: Int, groupByte: Byte): P2HMPK = {
      P2HMPK(calcHash(publicKeys, m), groupByte)
    }

    def unsafe(publicKeys: AVector[PublicKeyLike], m: Int, groupIndex: GroupIndex): P2HMPK = {
      unsafe(publicKeys, m, groupIndex.value.toByte)
    }

    private val safeHashSerde: Serde[Hash] = Groupless.safeSerde(serdeImpl[Hash])

    implicit val serde: Serde[P2HMPK] = {
      Serde.forProduct2(P2HMPK.unsafe, (p: P2HMPK) => (p.p2hmpkHash, p.groupByte))(
        safeHashSerde,
        serdeImpl[Byte]
      )
    }

    def decodeHash(bytes: ByteString): Option[Hash] = {
      safeHashSerde.deserialize(bytes).toOption
    }

    def fromDecodedBase58(bytes: ByteString, groupByte: Byte): Option[P2HMPK] = {
      decodeHash(bytes).map(P2HMPK(_, groupByte))
    }

    private val publicKeysSerde: Serde[AVector[PublicKeyLike]] = avectorSerde[PublicKeyLike]
    def calcHash(publicKeys: AVector[PublicKeyLike], m: Int): Hash = {
      assume(m > 0 && m <= publicKeys.length)
      val bytes =
        P2HMPKPrefix ++ publicKeysSerde.serialize(publicKeys) ++ serdeImpl[Int].serialize(m)
      Hash.hash(bytes)
    }
  }

  object Groupless {
    val groupByteLength: Int = 1

    def safeSerde[T](rawSerde: Serde[T]): Serde[T] = new Serde[T] {
      override def serialize(input: T): ByteString = {
        val rawBytes = rawSerde.serialize(input)
        val checksum = Checksum.calcAndSerialize(rawBytes)
        rawBytes ++ checksum
      }

      override def _deserialize(input: ByteString): SerdeResult[Staging[T]] = {
        for {
          dataResult <- rawSerde._deserialize(input)
          rawBytes = input.take(input.length - dataResult.rest.length)
          checksumResult <- Checksum.serde._deserialize(dataResult.rest)
          _              <- checksumResult.value.check(rawBytes)
        } yield Staging(dataResult.value, checksumResult.rest)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    private[vm] def defaultGroup(bytes: ByteString)(implicit config: GroupConfig): GroupIndex = {
      assume(bytes.nonEmpty)
      GroupIndex.unsafe(Bytes.toPosInt(bytes.last) % config.groups)
    }

    def hasExplicitGroupIndex(input: String): Boolean = {
      input.length > 2 && input(input.length - 2) == ':'
    }

    def calcScriptHint(preImage: ByteString, groupByte: Byte): ScriptHint = {
      val initialHint  = ScriptHint.fromHash(DjbHash.intHash(preImage)).value
      val xorResult    = Bytes.xorByte(initialHint)
      val byte0        = (initialHint >> 24).toByte
      val newByte0     = byte0 ^ xorResult ^ groupByte
      val newHintValue = (newByte0 << 24) | (initialHint & 0x00ffffff)
      ScriptHint.fromHash(newHintValue)
    }
  }

  trait MultiSig {
    def validate(pkLength: Int, m: Int): Boolean = m > 0 && m <= pkLength

    def validationErrorMsg(pkLength: Int, m: Int): String =
      s"Invalid m in m-of-n multisig: m=$m, n=$pkLength"
  }
}
// scalastyle:on number.of.methods
