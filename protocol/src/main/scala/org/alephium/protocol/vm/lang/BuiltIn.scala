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

package org.alephium.protocol.vm.lang

import scala.language.reflectiveCalls

import org.alephium.protocol.model.dustUtxoAmount
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler.{Error, FuncInfo}
import org.alephium.util.AVector

// scalastyle:off file.size.limit
// scalastyle:off number.of.methods
object BuiltIn {
  sealed trait BuiltIn[-Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def name: String
    def tag: Tag
    def signature: String
    def doc: String

    def isPublic: Boolean = true

    def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      throw Compiler.Error(s"Built-in function $name does not belong to contract ${typeId.name}")
    }
  }

  sealed trait Tag {
    override def toString: String = getClass.getSimpleName.init
  }
  object Tag {
    case object Contract     extends Tag
    case object SubContract  extends Tag
    case object Asset        extends Tag
    case object Utils        extends Tag
    case object Chain        extends Tag
    case object Conversion   extends Tag
    case object ByteVec      extends Tag
    case object Cryptography extends Tag
  }

  final case class SimpleBuiltIn[-Ctx <: StatelessContext](
      name: String,
      argsType: Seq[Type],
      returnType: Seq[Type],
      instrs: Seq[Instr[Ctx]],
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      isReadonly: Boolean,
      tag: Tag,
      argsName: Seq[String],
      doc: String
  ) extends BuiltIn[Ctx] {
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for builtin func $name, expected $argsType")
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]] = instrs

    def signature: String = {
      val args = argsName.zip(argsType).map { case (name, tpe) => s"$name:$tpe" }
      s"fn $name!(${args.mkString(", ")}) -> (${returnType.mkString(", ")})"
    }
  }

  // scalastyle:off parameter.number
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  object SimpleBuiltIn {
    private def tag[Ctx <: StatelessContext](tag: Tag) = new {
      def apply(
          name: String,
          argsType: Seq[Type],
          returnType: Seq[Type],
          instr: Instr[Ctx],
          argsName: Seq[String],
          doc: String,
          usePreapprovedAssets: Boolean = false,
          useAssetsInContract: Boolean = false,
          isReadonly: Boolean = true
      ): SimpleBuiltIn[Ctx] =
        SimpleBuiltIn(
          name,
          argsType,
          returnType,
          Seq(instr),
          usePreapprovedAssets,
          useAssetsInContract,
          isReadonly,
          tag,
          argsName,
          doc
        )
    }

    private[lang] val cryptography = tag[StatelessContext](Tag.Cryptography)
    private[lang] val chain        = tag[StatelessContext](Tag.Chain)
    private[lang] val conversion   = tag[StatelessContext](Tag.Conversion)
    private[lang] val byteVec      = tag[StatelessContext](Tag.ByteVec)
    private[lang] val asset        = tag[StatefulContext](Tag.Asset)
    private[lang] val contract     = tag[StatefulContext](Tag.Contract)
    private[lang] val subContract  = tag[StatefulContext](Tag.SubContract)

    private[BuiltIn] def utils[Ctx <: StatelessContext] = tag[Ctx](Tag.Utils)

    def hash(
        name: String,
        argsType: Seq[Type],
        returnType: Seq[Type],
        instr: Instr[StatelessContext]
    ): SimpleBuiltIn[StatelessContext] =
      cryptography(
        name,
        argsType,
        returnType,
        instr,
        argsName = Seq("data"),
        doc = s"Computes the ${name.capitalize} hash of the input."
      )
  }

  final case class ArgsTypeWithInstrs[-Ctx <: StatelessContext](
      argsTypes: Seq[Type],
      instrs: Seq[Instr[Ctx]]
  )

  final case class OverloadedSimpleBuiltIn[Ctx <: StatelessContext](
      name: String,
      argsTypeWithInstrs: Seq[ArgsTypeWithInstrs[Ctx]],
      returnType: Seq[Type],
      tag: Tag,
      signature: String,
      doc: String,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      isReadonly: Boolean
  ) extends BuiltIn[Ctx] {
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      assume(argsTypeWithInstrs.distinctBy(_.argsTypes).length == argsTypeWithInstrs.length)

      if (argsTypeWithInstrs.exists(_.argsTypes == inputType)) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]] = {
      argsTypeWithInstrs.find(_.argsTypes == inputType) match {
        case Some(ArgsTypeWithInstrs(_, instrs)) =>
          instrs
        case None =>
          throw Error(s"Invalid args type $inputType for builtin func $name")
      }
    }
  }

  object OverloadedSimpleBuiltIn {
    def contractWithtoken(
        name: String,
        argsTypeWithInstrs: Seq[ArgsTypeWithInstrs[StatefulContext]],
        returnType: Seq[Type],
        tag: Tag,
        argsName: Seq[String],
        doc: String,
        usePreapprovedAssets: Boolean,
        useAssetsInContract: Boolean,
        isReadonly: Boolean
    ): OverloadedSimpleBuiltIn[StatefulContext] = {
      val signature: String = {
        val args =
          argsName.zip(argsTypeWithInstrs(0).argsTypes).map { case (name, tpe) => s"$name:$tpe" }
        s"fn $name!(${args.mkString(", ")}, issueTo: Address) -> (${returnType.mkString(", ")})"
      }
      OverloadedSimpleBuiltIn(
        name,
        argsTypeWithInstrs,
        returnType,
        tag,
        signature,
        doc,
        usePreapprovedAssets,
        useAssetsInContract,
        isReadonly
      )
    }
  }

  sealed abstract class GenericStatelessBuiltIn(val name: String)
      extends BuiltIn[StatelessContext] {
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
    def isReadonly: Boolean           = true
  }

  val blake2b: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.hash("blake2b", Seq(Type.ByteVec), Seq(Type.ByteVec), Blake2b)
  val keccak256: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.hash("keccak256", Seq(Type.ByteVec), Seq(Type.ByteVec), Keccak256)
  val sha256: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.hash("sha256", Seq(Type.ByteVec), Seq(Type.ByteVec), Sha256)
  val sha3: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.hash("sha3", Seq(Type.ByteVec), Seq(Type.ByteVec), Sha3)
  val assert: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "assert",
      Seq[Type](Type.Bool, Type.U256),
      Seq.empty,
      AssertWithErrorCode,
      argsName = Seq("condition", "errorCode"),
      doc = "Tests internal errors or checks invariants."
    )
  val verifyTxSignature: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifyTxSignature",
      Seq(Type.ByteVec),
      Seq(),
      VerifyTxSignature,
      argsName = Seq("publicKey"),
      doc =
        "Verifies the transaction signature of a public key. The signature is signed against the transaction id."
    )
  val verifySecP256K1: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifySecP256K1",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifySecP256K1,
      argsName = Seq("data", "publicKey", "signature"),
      doc = s"Verifies the SecP256K1 signature of the input and public key."
    )
  val checkCaller: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "checkCaller",
      Seq[Type](Type.Bool, Type.U256),
      Seq.empty,
      AssertWithErrorCode,
      argsName = Seq("condition", "errorCode"),
      doc = s"Check conditions of the external caller of the function."
    )
  val verifyED25519: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifyED25519",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifyED25519,
      argsName = Seq("data", "publicKey", "signature"),
      doc = s"Verifies the ED25519 signature of the input and public key."
    )
  val ethEcRecover: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "ethEcRecover",
      Seq(Type.ByteVec, Type.ByteVec),
      Seq(Type.ByteVec),
      EthEcRecover,
      argsName = Seq("data", "signature"),
      doc = s"Recovers the ETH account that signed the data."
    )
  val networkId: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "networkId",
      Seq.empty,
      Seq(Type.ByteVec),
      NetworkId,
      Seq(),
      doc = "Returns the network id."
    )
  val blockTimeStamp: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "blockTimeStamp",
      Seq.empty,
      Seq(Type.U256),
      BlockTimeStamp,
      Seq(),
      doc = "Returns the block timestamp."
    )
  val blockTarget: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "blockTarget",
      Seq.empty,
      Seq(Type.U256),
      BlockTarget,
      Seq(),
      doc = "Returns the block difficulty target."
    )
  val txId: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "txId",
      Seq.empty,
      Seq(Type.ByteVec),
      TxId,
      Seq(),
      doc = "Returns the current transaction id."
    )
  val txInputAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "txInputAddress",
      Seq(Type.U256),
      Seq(Type.Address),
      TxInputAddressAt,
      argsName = Seq("txInputIndex"),
      doc = "Returns the n-th transaction input address."
    )
  val txInputsSize: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "txInputsSize",
      Seq.empty,
      Seq(Type.U256),
      TxInputsSize,
      Seq(),
      doc = "Returns the number of transaction inputs."
    )
  val verifyAbsoluteLocktime: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "verifyAbsoluteLocktime",
      Seq(Type.U256),
      Seq.empty,
      VerifyAbsoluteLocktime,
      argsName = Seq("lockUntil"),
      doc = "Verifies the absolute locktime for block timestamp."
    )
  val verifyRelativeLocktime: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "verifyRelativeLocktime",
      Seq(Type.U256, Type.U256),
      Seq.empty,
      VerifyRelativeLocktime,
      argsName = Seq("txInputIndex", "lockDuration"),
      doc = "Verifies the relative locktime for transaction input."
    )

  sealed abstract class ConversionBuiltIn(name: String) extends GenericStatelessBuiltIn(name) {
    def tag: Tag = Tag.Conversion

    def toType: Type

    def validTypes: AVector[Type]

    def validate(tpe: Type): Boolean = validTypes.contains(tpe) && (tpe != toType)

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType.length != 1 || !validate(inputType(0))) {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      } else {
        Seq(toType)
      }
    }
  }

  val toI256: ConversionBuiltIn = new ConversionBuiltIn("toI256") {
    val validTypes: AVector[Type] = AVector(Type.U256)

    override def toType: Type = Type.I256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.U256 => Seq(U256ToI256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }

    def signature: String = s"fn $name!(from: U256) -> (I256)"
    def doc: String       = "Converts U256 to I256."
  }
  val toU256: ConversionBuiltIn = new ConversionBuiltIn("toU256") {
    val validTypes: AVector[Type] = AVector(Type.I256)

    override def toType: Type = Type.U256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.I256 => Seq(I256ToU256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }

    def signature: String = s"fn $name!(from: I256) -> (U256)"
    def doc: String       = "Converts I256 to U256."
  }

  val toByteVec: ConversionBuiltIn = new ConversionBuiltIn("toByteVec") {
    val validTypes: AVector[Type] = AVector(Type.Bool, Type.I256, Type.U256, Type.Address)

    override def toType: Type = Type.ByteVec

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.Bool    => Seq(BoolToByteVec)
        case Type.I256    => Seq(I256ToByteVec)
        case Type.U256    => Seq(U256ToByteVec)
        case Type.Address => Seq(AddressToByteVec)
        case _            => throw new RuntimeException("Dead branch")
      }
    }

    def signature: String = s"fn $name!(from: Bool|I256|U256|Address) -> (ByteVec)"
    def doc: String       = "Converts Bool/I256/U256/Address to ByteVec"
  }

  val size: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.byteVec(
      "size",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      ByteVecSize,
      argsName = Seq("bytes"),
      doc = "Returns the size of the ByteVec."
    )

  val isAssetAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "isAssetAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsAssetAddress,
      argsName = Seq("address"),
      doc = "Returns whether an address is an asset address."
    )

  val isContractAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "isContractAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsContractAddress,
      argsName = Seq("address"),
      doc = "Returns whether an address is a contract address."
    )

  val byteVecSlice: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.byteVec(
      "byteVecSlice",
      Seq[Type](Type.ByteVec, Type.U256, Type.U256),
      Seq[Type](Type.ByteVec),
      ByteVecSlice,
      argsName = Seq("bytes"),
      doc = "Selects an interval of bytes."
    )

  val encodeToByteVec: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String = "encodeToByteVec"

    def tag: Tag                      = Tag.ByteVec
    override def isVariadic: Boolean  = true
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
    def isReadonly: Boolean           = true

    def getReturnType(inputType: Seq[Type]): Seq[Type] = Seq(Type.ByteVec)

    def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(Encode)

    def signature: String = s"fn ${name}(...any) -> (ByteVec)"
    def doc: String       = "Encodes inputs as ByteVec."
  }

  val zeros: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "zeros",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      Zeros,
      argsName = Seq("bytes"),
      doc = "Returns a ByteVec of zeros."
    )

  val u256To1Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To1Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To1Byte,
      argsName = Seq("u256"),
      doc = "Converts U256 to 1 byte."
    )

  val u256To2Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To2Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To2Byte,
      argsName = Seq("u256"),
      doc = "Converts U256 to 2 bytes."
    )

  val u256To4Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To4Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To4Byte,
      argsName = Seq("u256"),
      doc = "Converts U256 to 4 bytes."
    )

  val u256To8Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To8Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To8Byte,
      argsName = Seq("u256"),
      doc = "Converts U256 to 8 bytes."
    )

  val u256To16Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To16Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To16Byte,
      argsName = Seq("u256"),
      doc = "Converts U256 to 16 bytes."
    )

  val u256To32Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To32Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To32Byte,
      argsName = Seq("u256"),
      doc = "Converts U256 to 32 bytes."
    )

  val u256From1Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From1Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From1Byte,
      argsName = Seq("bytes"),
      doc = "Converts 1 byte to U256."
    )

  val u256From2Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From2Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From2Byte,
      argsName = Seq("bytes"),
      doc = "Converts 2 byte to U256."
    )

  val u256From4Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From4Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From4Byte,
      argsName = Seq("bytes"),
      doc = "Converts 4 byte to U256."
    )

  val u256From8Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From8Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From8Byte,
      argsName = Seq("bytes"),
      doc = "Converts 8 byte to U256."
    )

  val u256From16Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From16Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From16Byte,
      argsName = Seq("bytes"),
      doc = "Converts 16 byte to U256."
    )

  val u256From32Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From32Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From32Byte,
      argsName = Seq("bytes"),
      doc = "Converts 32 byte to U256."
    )

  val byteVecToAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "byteVecToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ByteVecToAddress,
      argsName = Seq("bytes"),
      doc = "Converts ByteVec to Address."
    )

  val contractIdToAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "contractIdToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ContractIdToAddress,
      argsName = Seq("contractId"),
      doc = "Converts contract id (ByteVec) to contract address (Address)."
    )

  val dustAmount: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "dustAmount",
      Seq.empty,
      Seq[Type](Type.U256),
      U256Const(Val.U256(dustUtxoAmount)),
      argsName = Seq(),
      doc = "Returns the dust amount of an UTXO."
    )

  val panic: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String                  = "panic"
    def tag: Tag                      = Tag.Utils
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
    def isReadonly                    = true
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType.nonEmpty && inputType != Seq(Type.U256)) {
        throw Compiler.Error(s"Invalid argument type for $name, optional U256 expected")
      }
      Seq(Type.Panic)
    }
    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      if (inputType.isEmpty) {
        Seq(ConstFalse, Assert)
      } else {
        Seq(ConstFalse, Swap, AssertWithErrorCode)
      }
    }

    def signature: String = s"fn $name!(errorCode?: U256) -> (Never)"
    def doc: String       = "Terminates the application immediately."
  }

  val blockHash: BuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "blockHash",
      Seq.empty,
      Seq(Type.ByteVec),
      BlockHash,
      Seq(),
      doc = "Returns the block hash of the current block."
    )

  val statelessFuncsSeq: Seq[(String, BuiltIn[StatelessContext])] = Seq(
    blake2b,
    keccak256,
    sha256,
    sha3,
    assert,
    checkCaller,
    verifyTxSignature,
    verifySecP256K1,
    verifyED25519,
    networkId,
    blockHash,
    blockTimeStamp,
    blockTarget,
    txId,
    txInputAddress,
    txInputsSize,
    verifyAbsoluteLocktime,
    verifyRelativeLocktime,
    toI256,
    toU256,
    toByteVec,
    size,
    isAssetAddress,
    isContractAddress,
    byteVecSlice,
    encodeToByteVec,
    zeros,
    contractIdToAddress,
    byteVecToAddress,
    u256To1Byte,
    u256To2Byte,
    u256To4Byte,
    u256To8Byte,
    u256To16Byte,
    u256To32Byte,
    u256From1Byte,
    u256From2Byte,
    u256From4Byte,
    u256From8Byte,
    u256From16Byte,
    u256From32Byte,
    ethEcRecover,
    dustAmount,
    panic
  ).map(f => f.name -> f)

  val statelessFuncs: Map[String, BuiltIn[StatelessContext]] = statelessFuncsSeq.toMap

  val approveAlph: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "approveAlph",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      ApproveAlph,
      isReadonly = false,
      argsName = Seq("address", "amount"),
      doc = "Approves ALPH for usage from the input assets of the function."
    )

  val approveToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "approveToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      ApproveToken,
      isReadonly = false,
      argsName = Seq("address", "tokenId", "amount"),
      doc = "Approves token for usage from the input assets of the function."
    )

  val alphRemaining: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "alphRemaining",
      Seq(Type.Address),
      Seq(Type.U256),
      AlphRemaining,
      isReadonly = true,
      argsName = Seq("address"),
      doc = "Returns the amount of the remaining ALPH in the input assets of the function."
    )

  val tokenRemaining: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "tokenRemaining",
      Seq[Type](Type.Address, Type.ByteVec),
      Seq(Type.U256),
      TokenRemaining,
      isReadonly = true,
      argsName = Seq("address", "tokenId"),
      doc = "Returns the amount of the remaining token amount in the input assets of the function."
    )

  val transferAlph: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferAlph",
      Seq[Type](Type.Address, Type.Address, Type.U256),
      Seq.empty,
      TransferAlph,
      isReadonly = false,
      argsName = Seq("fromAddress", "toAddress", "amount"),
      doc = "Transfers ALPH from the input assets of the function."
    )

  val transferAlphFromSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferAlphFromSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlphFromSelf,
      useAssetsInContract = true,
      isReadonly = false,
      argsName = Seq("toAddress", "amount"),
      doc = "Transfers the contract's ALPH from the input assets of the function."
    )

  val transferAlphToSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferAlphToSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlphToSelf,
      useAssetsInContract = true,
      isReadonly = false,
      argsName = Seq("fromAddress", "amount"),
      doc = "Transfers ALPH to the contract from the input asset of the function."
    )

  val transferToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferToken",
      Seq[Type](Type.Address, Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferToken,
      isReadonly = false,
      argsName = Seq("fromAddress", "toAddress", "tokenId", "amount"),
      doc = "Transfers token from the input assets of the function."
    )

  val transferTokenFromSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferTokenFromSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenFromSelf,
      useAssetsInContract = true,
      isReadonly = false,
      argsName = Seq("toAddress", "tokenId", "amount"),
      doc = "Transfers the contract's token from the input assets of the function."
    )

  val transferTokenToSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferTokenToSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenToSelf,
      useAssetsInContract = true,
      isReadonly = false,
      argsName = Seq("fromAddress", "tokenId", "amount"),
      doc = "Transfers token to the contract from the input assets of the function."
    )

  val burnToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "burnToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      BurnToken,
      isReadonly = false,
      argsName = Seq("address", "tokenId", "amount"),
      doc = "Burns token from the input assets of the function."
    )

  val lockApprovedAssets: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "lockApprovedAssets",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      LockApprovedAssets,
      usePreapprovedAssets = true,
      isReadonly = false,
      argsName = Seq("address", "amount"),
      doc = "Lock the current approved assets."
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def docContractFunction(
      issueToken: Boolean,
      copy: Boolean,
      subContract: Boolean,
      f: String = ""
  ): String = {
    s"Creates a new ${if (subContract) "sub-" else ""}contract" +
      s" ${if (issueToken) "with" else "without"} token issuance" +
      s"${if (copy) " by copying another contract's code" else ""}." +
      s"${if (copy) s" This costs less gas than ${f}!(...)" else ""}"
  }

  val createContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "createContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateContract,
      usePreapprovedAssets = true,
      isReadonly = false,
      argsName = Seq("code", "encodedFields"),
      doc = docContractFunction(issueToken = false, copy = false, subContract = false)
    )

  val createContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "createContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CreateContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.U256, Type.Address),
          Seq(CreateContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      isReadonly = false,
      tag = Tag.Contract,
      argsName = Seq("code", "encodedFields", "issueTokenAmount"),
      doc = docContractFunction(issueToken = true, copy = false, subContract = false)
    )

  val copyCreateContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "copyCreateContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateContract,
      usePreapprovedAssets = true,
      isReadonly = false,
      argsName = Seq("contractId", "encodedFields"),
      doc = docContractFunction(
        issueToken = false,
        copy = true,
        subContract = false,
        f = "createContract"
      )
    )

  val copyCreateContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "copyCreateContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CopyCreateContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.U256, Type.Address),
          Seq(CopyCreateContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      isReadonly = false,
      tag = Tag.Contract,
      argsName = Seq("contractId", "encodedFields", "issueTokenAmount"),
      doc = docContractFunction(
        issueToken = true,
        copy = true,
        subContract = false,
        f = "createContract"
      )
    )

  val createSubContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.subContract(
      "createSubContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateSubContract,
      usePreapprovedAssets = true,
      isReadonly = false,
      argsName = Seq("subContractPath", "code", "encodedFields"),
      doc = docContractFunction(issueToken = false, copy = false, subContract = true)
    )

  val createSubContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "createSubContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CreateSubContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256, Type.Address),
          Seq(CreateSubContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      isReadonly = false,
      tag = Tag.SubContract,
      argsName = Seq("subContractPath", "contractId", "encodedFields", "issueTokenAmount"),
      doc = docContractFunction(issueToken = true, copy = false, subContract = true)
    )

  val copyCreateSubContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.subContract(
      "copyCreateSubContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateSubContract,
      usePreapprovedAssets = true,
      isReadonly = false,
      argsName = Seq("subContractPath", "contractId", "encodedFields"),
      doc = docContractFunction(
        issueToken = false,
        copy = true,
        subContract = true,
        f = "createSubContract"
      )
    )

  val copyCreateSubContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "copyCreateSubContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CopyCreateSubContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256, Type.Address),
          Seq(CopyCreateSubContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      isReadonly = false,
      tag = Tag.SubContract,
      argsName = Seq("subContractPath", "contractId", "encodedFields", "issueTokenAmount"),
      doc = docContractFunction(
        issueToken = true,
        copy = true,
        subContract = true,
        f = "createSubContractWithToken"
      )
    )

  val destroySelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "destroySelf",
      Seq[Type](Type.Address),
      Seq.empty,
      DestroySelf,
      useAssetsInContract = true,
      isReadonly = false,
      argsName = Seq("refundAddress"),
      doc = "Destroys the contract."
    )

  val migrate: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "migrate",
      Seq[Type](Type.ByteVec),
      Seq.empty,
      MigrateSimple,
      isReadonly = false,
      argsName = Seq("newCode"),
      doc = "Migrates the code of the contract."
    )

  val migrateWithFields: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "migrateWithFields",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq.empty,
      MigrateWithFields,
      isReadonly = false,
      argsName = Seq("newCode", "newEncodedFields"),
      doc = "Migrates both the code and the fields of the contract."
    )

  val contractExists: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "contractExists",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Bool),
      ContractExists,
      argsName = Seq("contractId"),
      doc = "Checks whether the input contract id exists."
    )

  val selfAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "selfAddress",
      Seq.empty,
      Seq(Type.Address),
      SelfAddress,
      argsName = Seq(),
      doc = "Returns the address (Address) of the contract."
    )

  val selfContractId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "selfContractId",
      Seq.empty,
      Seq(Type.ByteVec),
      SelfContractId,
      argsName = Seq(),
      "Returns the id (ByteVec) of the contract."
    )

  val selfTokenId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "selfTokenId",
      Seq.empty,
      Seq(Type.ByteVec),
      SelfContractId,
      argsName = Seq(),
      doc = "Returns the token id (ByteVec) of the contract."
    )

  val callerContractId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "callerContractId",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerContractId,
      argsName = Seq(),
      doc = "Returns the contract id of the caller."
    )

  val callerAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "callerAddress",
      Seq.empty,
      Seq(Type.Address),
      CallerAddress,
      argsName = Seq(),
      doc = "Returns the address of the caller."
    )

  val isCalledFromTxScript: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "isCalledFromTxScript",
      Seq.empty,
      Seq(Type.Bool),
      IsCalledFromTxScript,
      argsName = Seq(),
      doc = "Checks whether the function is called by a TxScript."
    )

  val callerInitialStateHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "callerInitialStateHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerInitialStateHash,
      argsName = Seq(),
      doc = "Returns the initial state hash of the caller contract."
    )

  val callerCodeHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "callerCodeHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerCodeHash,
      argsName = Seq(),
      doc = "Returns the contract code hash of the caller contract."
    )

  val contractInitialStateHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "contractInitialStateHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractInitialStateHash,
      argsName = Seq("contractId"),
      doc = "Returns the initial state hash of the contract."
    )

  val contractCodeHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "contractCodeHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractCodeHash,
      argsName = Seq("contractId"),
      doc = "Returns the contract code hash of the contract."
    )

  sealed abstract private class SubContractBuiltIn extends BuiltIn[StatefulContext] {
    def name: String
    def tag: Tag                      = Tag.SubContract
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
    def isReadonly: Boolean           = true

    def genCode(inputType: Seq[Type]): Seq[Instr[StatefulContext]]
  }

  val subContractId: BuiltIn[StatefulContext] = new SubContractBuiltIn {
    val name: String = "subContractId"
    def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == Seq(Type.ByteVec)) {
        Seq(Type.ByteVec)
      } else {
        throw Error(s"Invalid argument type for $name, ByteVec expected")
      }
    }
    def genCode(inputType: Seq[Type]): Seq[Instr[StatefulContext]] = {
      Seq(SelfContractId, Swap, ByteVecConcat, Blake2b, Blake2b)
    }

    def signature: String = s"fn $name!(subContractPath: ByteVec) -> (ByteVec)"
    def doc: String       = "Returns the id of the sub contract of the contract."
  }

  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  val subContractIdOf: BuiltIn[StatefulContext] = new SubContractBuiltIn {
    val name: String = "subContractIdOf"
    def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (
        inputType.length == 2 &&
        inputType(0).isInstanceOf[Type.Contract] &&
        inputType(1) == Type.ByteVec
      ) {
        Seq(Type.ByteVec)
      } else {
        throw Error(s"Invalid argument type for $name, (Contract, ByteVec) expected")
      }
    }
    def genCode(inputType: Seq[Type]): Seq[Instr[StatefulContext]] = {
      Seq[Instr[StatefulContext]](ByteVecConcat, Blake2b, Blake2b)
    }
    def signature: String =
      s"fn $name!(contract: <Contract>, subContractPath: ByteVec) -> (ByteVec)"
    def doc: String = "Returns the id of the sub contract of the input contract."
  }

  val nullContractAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.utils(
      "nullContractAddress",
      Seq.empty,
      Seq[Type](Type.Address),
      NullContractAddress,
      argsName = Seq(),
      doc = "Returns the null contract address with contract id being zeros."
    )

  val statefulFuncsSeq: Seq[(String, BuiltIn[StatefulContext])] =
    statelessFuncsSeq ++ Seq(
      approveAlph,
      approveToken,
      alphRemaining,
      tokenRemaining,
      transferAlph,
      transferAlphFromSelf,
      transferAlphToSelf,
      transferToken,
      transferTokenFromSelf,
      transferTokenToSelf,
      burnToken,
      lockApprovedAssets,
      createContract,
      createContractWithToken,
      copyCreateContract,
      copyCreateContractWithToken,
      createSubContract,
      createSubContractWithToken,
      copyCreateSubContract,
      copyCreateSubContractWithToken,
      selfAddress,
      selfContractId,
      selfTokenId,
      callerContractId,
      callerAddress,
      contractInitialStateHash,
      contractCodeHash,
      callerInitialStateHash,
      callerCodeHash,
      contractExists,
      destroySelf,
      migrate,
      migrateWithFields,
      isCalledFromTxScript,
      subContractId,
      subContractIdOf,
      nullContractAddress
    ).map(f => f.name -> f)

  val statefulFuncs: Map[String, BuiltIn[StatefulContext]] = statefulFuncsSeq.toMap
}
