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

package org.alephium.ralph

import scala.language.reflectiveCalls

import org.alephium.protocol.model.dustUtxoAmount
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler.{Error, FuncInfo}
import org.alephium.util.AVector

// scalastyle:off file.size.limit
// scalastyle:off number.of.methods
object BuiltIn {
  sealed trait BuiltIn[-Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    lazy val funcId: Ast.FuncId = Ast.FuncId(name, isBuiltIn = true)
    def name: String
    def category: Category
    def signature: String
    def params: Seq[String]
    def returns: String
    def doc: String

    def isPublic: Boolean        = true
    def useUpdateFields: Boolean = false

    def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      throw Compiler.Error(s"Built-in function $name does not belong to contract ${typeId.name}")
    }
  }

  sealed trait Category {
    override def toString: String = getClass.getSimpleName.init
  }
  object Category {
    case object Contract     extends Category
    case object SubContract  extends Category
    case object Asset        extends Category
    case object Utils        extends Category
    case object Chain        extends Category
    case object Conversion   extends Category
    case object ByteVec      extends Category
    case object Cryptography extends Category
  }

  trait DocUtils {
    def name: String
    def returnType: Seq[Type]

    def argsCommentedName: Seq[(String, String)]
    def retComment: String

    def params: Seq[String] = {
      argsCommentedName.map { case (name, comment) =>
        s"@param $name $comment"
      }
    }

    def returns: String = s"@returns $retComment"
  }

  trait NoOverloadingUtils {
    def name: String
    def argsType: Seq[Type]
    def argsCommentedName: Seq[(String, String)]
    def returnType: Seq[Type]

    def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for builtin func $name, expected $argsType")
      }
    }

    def signature: String = {
      val args = argsCommentedName.zip(argsType).map { case ((name, _), tpe) => s"$name:$tpe" }
      s"fn $name!(${args.mkString(", ")}) -> (${returnType.mkString(", ")})"
    }
  }

  final case class SimpleBuiltIn[-Ctx <: StatelessContext](
      name: String,
      argsType: Seq[Type],
      returnType: Seq[Type],
      instrs: Seq[Instr[Ctx]],
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      category: Category,
      argsCommentedName: Seq[(String, String)],
      retComment: String,
      doc: String
  ) extends BuiltIn[Ctx]
      with DocUtils
      with NoOverloadingUtils {
    require(argsCommentedName.length == argsType.length)

    override def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]] = instrs
  }

  // scalastyle:off parameter.number
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  object SimpleBuiltIn {
    private def tag[Ctx <: StatelessContext](category: Category) = new {
      def apply(
          name: String,
          argsType: Seq[Type],
          returnType: Seq[Type],
          instr: Instr[Ctx],
          argsName: Seq[(String, String)],
          retComment: String,
          doc: String,
          usePreapprovedAssets: Boolean = false,
          useAssetsInContract: Boolean = false
      ): SimpleBuiltIn[Ctx] =
        SimpleBuiltIn(
          name,
          argsType,
          returnType,
          Seq(instr),
          usePreapprovedAssets,
          useAssetsInContract,
          category,
          argsName,
          retComment,
          doc
        )
    }

    private def simpleReturn[Ctx <: StatelessContext](category: Category) = new {
      def apply(
          name: String,
          argsType: Seq[Type],
          returnType: Seq[Type],
          instr: Instr[Ctx],
          argsName: Seq[(String, String)],
          retComment: String,
          usePreapprovedAssets: Boolean = false,
          useAssetsInContract: Boolean = false
      ): SimpleBuiltIn[Ctx] =
        SimpleBuiltIn(
          name,
          argsType,
          returnType,
          Seq(instr),
          usePreapprovedAssets,
          useAssetsInContract,
          category,
          argsName,
          retComment,
          doc = s"Returns $retComment."
        )
    }

    private[ralph] val cryptography = tag[StatelessContext](Category.Cryptography)
    private[ralph] val chain        = tag[StatelessContext](Category.Chain)
    private[ralph] val conversion   = tag[StatelessContext](Category.Conversion)
    private[ralph] val byteVec      = tag[StatelessContext](Category.ByteVec)
    private[ralph] val asset        = tag[StatefulContext](Category.Asset)
    private[ralph] val contract     = tag[StatefulContext](Category.Contract)
    private[ralph] val subContract  = tag[StatefulContext](Category.SubContract)

    private[ralph] val chainSimple    = simpleReturn[StatelessContext](Category.Chain)
    private[ralph] val byteVecSimple  = simpleReturn[StatelessContext](Category.ByteVec)
    private[ralph] val assetSimple    = simpleReturn[StatefulContext](Category.Asset)
    private[ralph] val contractSimple = simpleReturn[StatefulContext](Category.Contract)

    private[BuiltIn] def utils[Ctx <: StatelessContext]       = tag[Ctx](Category.Utils)
    private[BuiltIn] def utilsSimple[Ctx <: StatelessContext] = simpleReturn[Ctx](Category.Utils)

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
        argsName = Seq("data" -> "the input data to be hashed"),
        retComment = "the hash result",
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
      category: Category,
      argsCommentedName: Seq[(String, String)],
      retComment: String,
      signature: String,
      doc: String,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean
  ) extends BuiltIn[Ctx]
      with DocUtils {
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
        category: Category,
        argsName: Seq[(String, String)],
        doc: String,
        usePreapprovedAssets: Boolean,
        useAssetsInContract: Boolean
    ): OverloadedSimpleBuiltIn[StatefulContext] = {
      val signature: String = {
        val args =
          argsName.zip(argsTypeWithInstrs(0).argsTypes).map { case ((name, _), tpe) =>
            s"$name:$tpe"
          }
        s"fn $name!(${args.mkString(", ")}, issueTo?:Address) -> (${returnType.mkString(", ")})"
      }
      OverloadedSimpleBuiltIn(
        name,
        argsTypeWithInstrs,
        returnType,
        category,
        argsName :+ ("issueTo (optional)" -> "a designated address to receive issued token"),
        retComment = "the id of the created contract",
        signature,
        doc,
        usePreapprovedAssets,
        useAssetsInContract
      )
    }
  }

  sealed abstract class GenericStatelessBuiltIn(val name: String)
      extends BuiltIn[StatelessContext] {
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
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
      argsName = Seq(
        "condition" -> "the condition to be checked",
        "errorCode" -> "the error code to throw if the check fails"
      ),
      retComment = "true if the condition is satisfied, false otherwise",
      doc = "Tests the condition or checks invariants."
    )
  val verifyTxSignature: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifyTxSignature",
      Seq(Type.ByteVec),
      Seq(),
      VerifyTxSignature,
      argsName = Seq("publicKey" -> "the public key (33 bytes) of the signer"),
      retComment = "true if the signature is valid, false otherwise",
      doc =
        "Verifies the transaction SecP256K1 signature of a public key. The signature is signed against the transaction id."
    )
  val verifySecP256K1: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifySecP256K1",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifySecP256K1,
      argsName = Seq(
        "data"      -> "the data (32 bytes) that was supposed to have been signed",
        "publicKey" -> "the public key (33 bytes) of the signer",
        "signature" -> "the signature (64 bytes) value"
      ),
      retComment = "true if the signature is valid, false otherwise",
      doc = s"Verifies the SecP256K1 signature of the input and public key."
    )
  val checkCaller: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "checkCaller",
      Seq[Type](Type.Bool, Type.U256),
      Seq.empty,
      AssertWithErrorCode,
      argsName = Seq(
        "condition" -> "the condition to be checked",
        "errorCode" -> "the error code to throw if the check fails"
      ),
      retComment = "",
      doc = s"Checks conditions of the external caller of the function."
    )
  val verifyED25519: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifyED25519",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifyED25519,
      argsName = Seq(
        "data"      -> "the data (32 bytes) that was supposed to have been signed",
        "publicKey" -> "the public key (32 bytes) of the signer",
        "signature" -> "the signature value (64 bytes)"
      ),
      retComment = "true if the signature is valid, false otherwise",
      doc = s"Verifies the ED25519 signature of the input and public key."
    )
  val verifyBIP340Schnorr: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "verifyBIP340Schnorr",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifyBIP340Schnorr,
      argsName = Seq(
        "data"      -> "the data (32 bytes) that was supposed to have been signed",
        "publicKey" -> "the public key (32 bytes) of the signer",
        "signature" -> "the signature value (64 bytes)"
      ),
      retComment = "true if the signature is valid, false otherwise",
      doc = s"Verifies the BIP340 Schnorr signature of the input and public key."
    )
  val ethEcRecover: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.cryptography(
      "ethEcRecover",
      Seq(Type.ByteVec, Type.ByteVec),
      Seq(Type.ByteVec),
      EthEcRecover,
      argsName = Seq(
        "data"      -> "the data that was supposed to have been signed",
        "signature" -> "the signature value"
      ),
      retComment = "the ETH account that signed the data",
      doc = s"Recovers the ETH account that signed the data."
    )
  val networkId: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "networkId",
      Seq.empty,
      Seq(Type.ByteVec),
      NetworkId,
      Seq(),
      retComment = "the network id (a single byte)"
    )
  val blockTimeStamp: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "blockTimeStamp",
      Seq.empty,
      Seq(Type.U256),
      BlockTimeStamp,
      Seq(),
      retComment = "the block timestamp"
    )
  val blockTarget: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "blockTarget",
      Seq.empty,
      Seq(Type.U256),
      BlockTarget,
      Seq(),
      retComment = "the block difficulty target"
    )
  val txId: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "txId",
      Seq.empty,
      Seq(Type.ByteVec),
      TxId,
      Seq(),
      retComment = "the current transaction id"
    )
  val txInputAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "txInputAddress",
      Seq(Type.U256),
      Seq(Type.Address),
      TxInputAddressAt,
      argsName = Seq("txInputIndex" -> "the index of the transaction input"),
      retComment = "the n-th transaction input address"
    )
  val txInputsSize: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "txInputsSize",
      Seq.empty,
      Seq(Type.U256),
      TxInputsSize,
      Seq(),
      retComment = "the number of transaction inputs"
    )
  val txGasPrice: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "txGasPrice",
      Seq.empty,
      Seq(Type.U256),
      TxGasPrice,
      Seq.empty,
      retComment = "the current transaction gas price"
    )
  val txGasAmount: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "txGasAmount",
      Seq.empty,
      Seq(Type.U256),
      TxGasAmount,
      Seq.empty,
      retComment = "the current transaction gas amount"
    )
  val txGasFee: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "txGasFee",
      Seq.empty,
      Seq(Type.U256),
      TxGasFee,
      Seq.empty,
      retComment = "the current transaction gas fee"
    )
  val verifyAbsoluteLocktime: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "verifyAbsoluteLocktime",
      Seq(Type.U256),
      Seq.empty,
      VerifyAbsoluteLocktime,
      argsName = Seq("lockUntil" -> "the timestamp until which the lock is valid"),
      retComment = "",
      doc = "Verifies that the absolute locktime is before the block timestamp, otherwise it fails."
    )
  val verifyRelativeLocktime: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chain(
      "verifyRelativeLocktime",
      Seq(Type.U256, Type.U256),
      Seq.empty,
      VerifyRelativeLocktime,
      argsName = Seq(
        "txInputIndex" -> "the index of the transaction input",
        "lockDuration" -> "the duration that the input is locked for"
      ),
      retComment = "",
      doc =
        "Verifies that the input's creation timestamp + lock duration is before the block timestamp, otherwise it fails."
    )

  sealed abstract class ConversionBuiltIn(name: String) extends GenericStatelessBuiltIn(name) {
    def category: Category = Category.Conversion

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

    def signature: String   = s"fn $name!(from:U256) -> (I256)"
    val params: Seq[String] = Seq("@param from a U256 to be converted")
    val returns: String     = "@returns a I256"
    def doc: String         = "Converts U256 to I256."
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

    def signature: String   = s"fn $name!(from:I256) -> (U256)"
    val params: Seq[String] = Seq("@param from a I256 to be converted")
    val returns: String     = "@returns a U256"
    val doc: String         = "Converts I256 to U256."
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

    def signature: String   = s"fn $name!(from:Bool|I256|U256|Address) -> (ByteVec)"
    val params: Seq[String] = Seq("@param from a Bool|I256|U256|Address to be converted")
    val returns: String     = "@returns a ByteVec"
    val doc: String         = "Converts Bool/I256/U256/Address to ByteVec"
  }

  val size: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.byteVecSimple(
      "size",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      ByteVecSize,
      argsName = Seq("bytes" -> "a ByteVec"),
      retComment = "the size of the ByteVec"
    )

  val isAssetAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "isAssetAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsAssetAddress,
      argsName = Seq("address" -> "the input address to be tested"),
      retComment = "true if the address is an asset address, false otherwise",
      doc = "Returns whether an address is an asset address."
    )

  val isContractAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utils(
      "isContractAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsContractAddress,
      argsName = Seq("address" -> "the input address to be tested"),
      retComment = "true if the address is a contract address, false otherwise",
      doc = "Returns whether an address is a contract address."
    )

  val byteVecSlice: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.byteVec(
      "byteVecSlice",
      Seq[Type](Type.ByteVec, Type.U256, Type.U256),
      Seq[Type](Type.ByteVec),
      ByteVecSlice,
      argsName = Seq(
        "bytes" -> "a ByteVec",
        "from"  -> "the lowest index to include from the ByteVec",
        "until" -> "the lowest index to exclude from the ByteVec"
      ),
      retComment =
        "a ByteVec containing the elements greater than or equal to index from extending up to (but not including) index until of this ByteVec",
      doc = "Selects an interval of bytes."
    )

  val encodeToByteVec: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String = "encodeToByteVec"

    def category: Category            = Category.ByteVec
    override def isVariadic: Boolean  = true
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false

    def getReturnType(inputType: Seq[Type]): Seq[Type] = Seq(Type.ByteVec)

    def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(Encode)

    def signature: String   = s"fn $name!(...any) -> (ByteVec)"
    def params: Seq[String] = Seq("@param any a sequence of input values")
    def returns: String     = "@returns a ByteVec encoding the inputs"
    def doc: String         = "Encodes inputs as big-endian ByteVec."
  }

  val zeros: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.utilsSimple(
      "zeros",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      Zeros,
      argsName = Seq("n" -> "the number of zeros"),
      retComment = "a ByteVec of zeros"
    )

  val u256To1Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To1Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To1Byte,
      argsName = Seq("u256" -> "the input U256"),
      retComment = "1 byte",
      doc = "Converts U256 to 1 byte."
    )

  val u256To2Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To2Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To2Byte,
      argsName = Seq("u256" -> "the input U256"),
      retComment = "2 bytes",
      doc = "Converts U256 to 2 big-endian bytes."
    )

  val u256To4Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To4Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To4Byte,
      argsName = Seq("u256" -> "the input U256"),
      retComment = "4 bytes",
      doc = "Converts U256 to 4 big-endian bytes."
    )

  val u256To8Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To8Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To8Byte,
      argsName = Seq("u256" -> "the input U256"),
      retComment = "8 bytes",
      doc = "Converts U256 to 8 big-endian bytes."
    )

  val u256To16Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To16Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To16Byte,
      argsName = Seq("u256" -> "the input U256"),
      retComment = "16 bytes",
      doc = "Converts U256 to 16 big-endian bytes."
    )

  val u256To32Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256To32Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To32Byte,
      argsName = Seq("u256" -> "the input U256"),
      retComment = "32 bytes",
      doc = "Converts U256 to 32 big-endian bytes."
    )

  val u256From1Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From1Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From1Byte,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an U256",
      doc = "Converts 1 byte to U256."
    )

  val u256From2Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From2Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From2Byte,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an U256",
      doc = "Converts 2 big-endian bytes to U256."
    )

  val u256From4Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From4Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From4Byte,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an U256",
      doc = "Converts 4 big-endian bytes to U256."
    )

  val u256From8Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From8Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From8Byte,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an U256",
      doc = "Converts 8 big-endian bytes to U256."
    )

  val u256From16Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From16Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From16Byte,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an U256",
      doc = "Converts 16 big-endian bytes to U256."
    )

  val u256From32Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "u256From32Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From32Byte,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an U256",
      doc = "Converts 32 big-endian bytes to U256."
    )

  val byteVecToAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "byteVecToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ByteVecToAddress,
      argsName = Seq("bytes" -> "the input ByteVec"),
      retComment = "an Address",
      doc = "Converts ByteVec to Address."
    )

  val contractIdToAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.conversion(
      "contractIdToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ContractIdToAddress,
      argsName = Seq("contractId" -> "the input contract id"),
      retComment = "a contract Address",
      doc = "Converts contract id (ByteVec) to contract address (Address)."
    )

  val dustAmount: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "dustAmount",
      Seq.empty,
      Seq[Type](Type.U256),
      U256Const(Val.U256(dustUtxoAmount)),
      argsName = Seq(),
      retComment = "the dust amount of an UTXO"
    )

  val panic: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String                  = "panic"
    def category: Category            = Category.Utils
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
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
    def params: Seq[String] = Seq(
      "@param errorCode (optional) the error code to be thrown when the panic!(...) is called"
    )
    def returns: String = "@returns "
    def doc: String     = "Terminates the application immediately."
  }

  val blockHash: BuiltIn[StatelessContext] =
    SimpleBuiltIn.chainSimple(
      "blockHash",
      Seq.empty,
      Seq(Type.ByteVec),
      BlockHash,
      Seq(),
      retComment = "the block hash of the current block"
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
    verifyBIP340Schnorr,
    networkId,
    blockHash,
    blockTimeStamp,
    blockTarget,
    txId,
    txInputAddress,
    txInputsSize,
    txGasPrice,
    txGasAmount,
    txGasFee,
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

  val approveToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "approveToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      ApproveToken,
      argsName = Seq(
        "fromAddress" -> "the address to approve token from",
        "tokenId"     -> "the token to be approved",
        "amount"      -> "the amount of the token to be approved"
      ),
      retComment = "",
      doc = "Approves the usage of certain amount of token from the given address"
    )

  val tokenRemaining: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.assetSimple(
      "tokenRemaining",
      Seq[Type](Type.Address, Type.ByteVec),
      Seq(Type.U256),
      TokenRemaining,
      argsName = Seq("address" -> "the input address", "tokenId" -> "the token id"),
      retComment = "the amount of the remaining token amount in the input assets of the function"
    )

  val transferToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferToken",
      Seq[Type](Type.Address, Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferToken,
      argsName = Seq(
        "fromAddress" -> "the address to transfer token from",
        "toAddress"   -> "the address to transfer token to",
        "tokenId"     -> "the token to be transferred",
        "amount"      -> "the amount of token to be transferred"
      ),
      retComment = "",
      doc = "Transfers token from the input assets of the function."
    )

  val transferTokenFromSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferTokenFromSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenFromSelf,
      useAssetsInContract = true,
      argsName = Seq(
        "toAddress" -> "the address to transfer token to",
        "tokenId"   -> "the token to be transferred",
        "amount"    -> "the amount of token to be transferred"
      ),
      retComment = "",
      doc = "Transfers the contract's token from the input assets of the function."
    )

  val transferTokenToSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "transferTokenToSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenToSelf,
      useAssetsInContract = true,
      argsName = Seq(
        "fromAddress" -> "the address to transfer token from",
        "tokenId"     -> "the token to be transferred",
        "amount"      -> "the amount of token to be transferred"
      ),
      retComment = "",
      doc = "Transfers token to the contract from the input assets of the function."
    )

  val burnToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "burnToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      BurnToken,
      argsName = Seq(
        "address" -> "the address to burn token from",
        "tokenId" -> "the token to be burnt",
        "amount"  -> "the amount of token to be burnt"
      ),
      retComment = "",
      doc = "Burns token from the input assets of the function."
    )

  val lockApprovedAssets: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.asset(
      "lockApprovedAssets",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      LockApprovedAssets,
      usePreapprovedAssets = true,
      argsName = Seq(
        "address"   -> "the address to lock assets to",
        "timestamp" -> "the timestamp that the assets will be locked until"
      ),
      retComment = "",
      doc = "Locks the current approved assets."
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def docContractFunction(
      issueToken: Boolean,
      copy: Boolean,
      subContract: Boolean,
      costLessThan: String = ""
  ): String = {
    s"Creates a new ${if (subContract) "sub-" else ""}contract" +
      s" ${if (issueToken) "with" else "without"} token issuance" +
      s"${if (copy) " by copying another contract's code" else ""}." +
      s"${if (copy) s" This costs less gas than ${costLessThan}!(...)." else ""}"
  }

  val createContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "createContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateContract,
      usePreapprovedAssets = true,
      argsName = Seq(
        "bytecode"         -> "the bytecode of the contract to be created",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec"
      ),
      retComment = "the id of the created contract",
      doc = docContractFunction(issueToken = false, copy = false, subContract = false)
    )

  val createContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "createContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CreateContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256, Type.Address),
          Seq(CreateContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      category = Category.Contract,
      argsName = Seq(
        "bytecode"         -> "the bytecode of the contract to be created",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec",
        "issueTokenAmount" -> "the amount of token to be issued"
      ),
      doc = docContractFunction(issueToken = true, copy = false, subContract = false)
    )

  val copyCreateContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "copyCreateContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateContract,
      usePreapprovedAssets = true,
      argsName = Seq(
        "contractId"       -> "the id of the contract to be copied",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec"
      ),
      retComment = "the id of the created contract",
      doc = docContractFunction(
        issueToken = false,
        copy = true,
        subContract = false,
        costLessThan = "createContract"
      )
    )

  val copyCreateContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "copyCreateContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CopyCreateContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256, Type.Address),
          Seq(CopyCreateContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      category = Category.Contract,
      argsName = Seq(
        "contractId"       -> "the id of the contract to be copied",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec",
        "issueTokenAmount" -> "the amount of token to be issued"
      ),
      doc = docContractFunction(
        issueToken = true,
        copy = true,
        subContract = false,
        costLessThan = "createContractWithToken"
      )
    )

  val createSubContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.subContract(
      "createSubContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateSubContract,
      usePreapprovedAssets = true,
      argsName = Seq(
        "subContractPath"  -> "the path of the sub-contract to be created",
        "bytecode"         -> "the bytecode of the sub-contract to be created",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec"
      ),
      retComment = "the id of the created contract",
      doc = docContractFunction(issueToken = false, copy = false, subContract = true)
    )

  val createSubContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "createSubContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CreateSubContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](
            Type.ByteVec,
            Type.ByteVec,
            Type.ByteVec,
            Type.ByteVec,
            Type.U256,
            Type.Address
          ),
          Seq(CreateSubContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      category = Category.SubContract,
      argsName = Seq(
        "subContractPath"  -> "the path of the sub-contract to be created",
        "bytecode"         -> "the bytecode of the sub-contract to be created",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec",
        "issueTokenAmount" -> "the amount of token to be issued"
      ),
      doc = docContractFunction(issueToken = true, copy = false, subContract = true)
    )

  val copyCreateSubContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.subContract(
      "copyCreateSubContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateSubContract,
      usePreapprovedAssets = true,
      argsName = Seq(
        "subContractPath"  -> "the path of the sub-contract to be created",
        "contractId"       -> "the id of the contract to be copied",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec"
      ),
      retComment = "the id of the created contract",
      doc = docContractFunction(
        issueToken = false,
        copy = true,
        subContract = true,
        costLessThan = "createSubContract"
      )
    )

  val copyCreateSubContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn.contractWithtoken(
      "copyCreateSubContractWithToken",
      argsTypeWithInstrs = Seq(
        ArgsTypeWithInstrs(
          Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.ByteVec, Type.U256),
          Seq(CopyCreateSubContractWithToken)
        ),
        ArgsTypeWithInstrs(
          Seq[Type](
            Type.ByteVec,
            Type.ByteVec,
            Type.ByteVec,
            Type.ByteVec,
            Type.U256,
            Type.Address
          ),
          Seq(CopyCreateSubContractAndTransferToken)
        )
      ),
      Seq[Type](Type.ByteVec),
      usePreapprovedAssets = true,
      useAssetsInContract = false,
      category = Category.SubContract,
      argsName = Seq(
        "subContractPath"  -> "the path of the sub-contract to be created",
        "contractId"       -> "the id of the contract to be copied",
        "encodedImmFields" -> "the encoded immutable fields as a ByteVec",
        "encodedMutFields" -> "the encoded mutable fields as a ByteVec",
        "issueTokenAmount" -> "the amount of token to be issued"
      ),
      doc = docContractFunction(
        issueToken = true,
        copy = true,
        subContract = true,
        costLessThan = "createSubContractWithToken"
      )
    )

  val destroySelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "destroySelf",
      Seq[Type](Type.Address),
      Seq.empty,
      DestroySelf,
      useAssetsInContract = true,
      argsName =
        Seq("refundAddress" -> "the address to receive the remaining assets in the contract"),
      retComment = "",
      doc = "Destroys the contract and transfer the remaining assets to a designated address."
    )

  val migrate: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "migrate",
      Seq[Type](Type.ByteVec),
      Seq.empty,
      MigrateSimple,
      argsName = Seq("newBytecode" -> "the new bytecode for the contract to migrate to"),
      retComment = "",
      doc = "Migrates the code of the contract."
    )

  val migrateWithFields: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "migrateWithFields",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      MigrateWithFields,
      argsName = Seq(
        "newBytecode"         -> "the bytecode for the contract to migrate to",
        "newEncodedImmFields" -> "the encoded immutable fields for the contract to migrate to",
        "newEncodedMutFields" -> "the encoded mutable fields for the contract to migrate to"
      ),
      retComment = "",
      doc = "Migrates both the code and the fields of the contract."
    )

  val contractExists: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "contractExists",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Bool),
      ContractExists,
      argsName = Seq("contractId" -> "the input contract id to be tested"),
      retComment = "ture if the contract exists on the chain, false otherwise",
      doc = "Checks whether the contract exists with the given id."
    )

  val selfAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "selfAddress",
      Seq.empty,
      Seq(Type.Address),
      SelfAddress,
      argsName = Seq(),
      retComment = "the address of the contract"
    )

  val selfContractId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "selfContractId",
      Seq.empty,
      Seq(Type.ByteVec),
      SelfContractId,
      argsName = Seq(),
      retComment = "the id (ByteVec) of the contract"
    )

  val selfTokenId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "selfTokenId",
      Seq.empty,
      Seq(Type.ByteVec),
      SelfContractId,
      argsName = Seq(),
      retComment = "the token id (ByteVec) of the contract"
    )

  val callerContractId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "callerContractId",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerContractId,
      argsName = Seq(),
      retComment = "the contract id of the caller"
    )

  val callerAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "callerAddress",
      Seq.empty,
      Seq(Type.Address),
      CallerAddress,
      argsName = Seq(),
      retComment =
        "the address of the caller. When used in a TxScript, it returns the unique input address if the input addresses are the same, otherwise it fails"
    )

  val isCalledFromTxScript: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contract(
      "isCalledFromTxScript",
      Seq.empty,
      Seq(Type.Bool),
      IsCalledFromTxScript,
      argsName = Seq(),
      retComment = "true if the function is called by a TxScript, false otherwise",
      doc = "Checks whether the function is called by a TxScript."
    )

  val callerInitialStateHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "callerInitialStateHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerInitialStateHash,
      argsName = Seq(),
      retComment = "the initial state hash of the caller contract"
    )

  val callerCodeHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "callerCodeHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerCodeHash,
      argsName = Seq(),
      retComment = "the contract code hash of the caller contract"
    )

  val contractInitialStateHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "contractInitialStateHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractInitialStateHash,
      argsName = Seq("contractId" -> "the id of the input contract"),
      retComment = "the initial state hash of the contract"
    )

  val contractCodeHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.contractSimple(
      "contractCodeHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractCodeHash,
      argsName = Seq("contractId" -> "the id of the input contract"),
      retComment = "the contract code hash of the contract"
    )

  sealed abstract private class SubContractBuiltIn extends BuiltIn[StatefulContext] with DocUtils {
    def name: String
    def category: Category            = Category.SubContract
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false

    def returnType: Seq[Type] = Seq(Type.ByteVec)

    def genCode(inputType: Seq[Type]): Seq[Instr[StatefulContext]]

    val retComment: String = "the id of the sub contract"
    def doc: String        = s"Returns ${retComment}."
  }

  val subContractId: BuiltIn[StatefulContext] = new SubContractBuiltIn with NoOverloadingUtils {
    val name: String        = "subContractId"
    def argsType: Seq[Type] = Seq(Type.ByteVec)

    val argsCommentedName: Seq[(String, String)] = Seq(
      "subContractPath" -> "the path of the sub-contract"
    )

    def genCode(inputType: Seq[Type]): Seq[Instr[StatefulContext]] = {
      Seq(SubContractId)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  val subContractIdOf: BuiltIn[StatefulContext] = new SubContractBuiltIn {
    val name: String = "subContractIdOf"
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
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
      Seq[Instr[StatefulContext]](SubContractIdOf)
    }

    val argsCommentedName: Seq[(String, String)] =
      Seq(
        "contract"        -> "the parent contract of the sub-contract",
        "subContractPath" -> "the path of the sub-contract"
      )

    override def signature: String =
      s"fn $name!(contract:<Contract>, subContractPath:ByteVec) -> (ByteVec)"
  }

  val nullContractAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn.utilsSimple(
      "nullContractAddress",
      Seq.empty,
      Seq[Type](Type.Address),
      NullContractAddress,
      argsName = Seq(),
      retComment = "the null contract address with contract id being zeros"
    )

  val statefulFuncsSeq: Seq[(String, BuiltIn[StatefulContext])] =
    statelessFuncsSeq ++ Seq(
      approveToken,
      tokenRemaining,
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
