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

import org.alephium.protocol.model.{dustUtxoAmount, ContractId}
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler.{Error, FuncInfo}
import org.alephium.util.AVector

// scalastyle:off file.size.limit
// scalastyle:off number.of.methods
object BuiltIn {
  sealed trait BuiltIn[-Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def name: String

    def isPublic: Boolean = true

    def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      throw Compiler.Error(s"Built-in function $name does not belong to contract ${typeId.name}")
    }
  }

  final case class SimpleBuiltIn[-Ctx <: StatelessContext](
      name: String,
      argsType: Seq[Type],
      returnType: Seq[Type],
      instrs: Seq[Instr[Ctx]],
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      isReadonly: Boolean
  ) extends BuiltIn[Ctx] {
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]] = instrs
  }

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  object SimpleBuiltIn {
    def apply[Ctx <: StatelessContext](
        name: String,
        argsType: Seq[Type],
        returnType: Seq[Type],
        instr: Instr[Ctx],
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
        isReadonly
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

  sealed abstract class GenericStatelessBuiltIn(val name: String)
      extends BuiltIn[StatelessContext] {
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
    def isReadonly: Boolean           = true
  }

  val blake2b: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("blake2b", Seq(Type.ByteVec), Seq(Type.ByteVec), Blake2b)
  val keccak256: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("keccak256", Seq(Type.ByteVec), Seq(Type.ByteVec), Keccak256)
  val sha256: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("sha256", Seq(Type.ByteVec), Seq(Type.ByteVec), Sha256)
  val sha3: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("sha3", Seq(Type.ByteVec), Seq(Type.ByteVec), Sha3)
  val assert: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "assert",
      Seq[Type](Type.Bool, Type.U256),
      Seq.empty,
      AssertWithErrorCode
    )
  val verifyTxSignature: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("verifyTxSignature", Seq(Type.ByteVec), Seq(), VerifyTxSignature)
  val verifySecP256K1: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "verifySecP256K1",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifySecP256K1
    )
  val checkCaller: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "checkCaller",
      Seq[Type](Type.Bool, Type.U256),
      Seq.empty,
      AssertWithErrorCode
    )
  val verifyED25519: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "verifyED25519",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifyED25519
    )
  val ethEcRecover: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "ethEcRecover",
      Seq(Type.ByteVec, Type.ByteVec),
      Seq(Type.ByteVec),
      EthEcRecover
    )
  val networkId: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("networkId", Seq.empty, Seq(Type.ByteVec), NetworkId)
  val blockTimeStamp: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("blockTimeStamp", Seq.empty, Seq(Type.U256), BlockTimeStamp)
  val blockTarget: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("blockTarget", Seq.empty, Seq(Type.U256), BlockTarget)
  val txId: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("txId", Seq.empty, Seq(Type.ByteVec), TxId)
  val txInputAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("txInputAddress", Seq(Type.U256), Seq(Type.Address), TxInputAddressAt)
  val txInputsSize: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("txInputsSize", Seq.empty, Seq(Type.U256), TxInputsSize)
  val verifyAbsoluteLocktime: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "verifyAbsoluteLocktime",
      Seq(Type.U256),
      Seq.empty,
      VerifyAbsoluteLocktime
    )
  val verifyRelativeLocktime: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "verifyRelativeLocktime",
      Seq(Type.U256, Type.U256),
      Seq.empty,
      VerifyRelativeLocktime
    )

  sealed abstract class ConversionBuiltIn(name: String) extends GenericStatelessBuiltIn(name) {
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
  }

  val size: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn("size", Seq[Type](Type.ByteVec), Seq[Type](Type.U256), ByteVecSize)

  val isAssetAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "isAssetAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsAssetAddress
    )

  val isContractAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "isContractAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsContractAddress
    )

  val byteVecSlice: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "byteVecSlice",
      Seq[Type](Type.ByteVec, Type.U256, Type.U256),
      Seq[Type](Type.ByteVec),
      ByteVecSlice
    )

  val encodeToByteVec: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String = "encodeToByteVec"

    override def isVariadic: Boolean  = true
    def usePreapprovedAssets: Boolean = false
    def useAssetsInContract: Boolean  = false
    def isReadonly: Boolean           = true

    def getReturnType(inputType: Seq[Type]): Seq[Type] = Seq(Type.ByteVec)

    def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(Encode)
  }

  val zeros: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "zeros",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      Zeros
    )

  val u256To1Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256To1Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To1Byte
    )

  val u256To2Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256To2Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To2Byte
    )

  val u256To4Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256To4Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To4Byte
    )

  val u256To8Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256To8Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To8Byte
    )

  val u256To16Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256To16Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To16Byte
    )

  val u256To32Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256To32Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To32Byte
    )

  val u256From1Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256From1Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From1Byte
    )

  val u256From2Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256From2Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From2Byte
    )

  val u256From4Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256From4Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From4Byte
    )

  val u256From8Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256From8Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From8Byte
    )

  val u256From16Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256From16Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From16Byte
    )

  val u256From32Byte: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "u256From32Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From32Byte
    )

  val byteVecToAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "byteVecToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ByteVecToAddress
    )

  val contractIdToAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "contractIdToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ContractIdToAddress
    )

  val nullAddress: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "nullAddress",
      Seq.empty,
      Seq[Type](Type.Address),
      AddressConst(Val.Address(LockupScript.p2c(ContractId.zero)))
    )

  val dustAmount: SimpleBuiltIn[StatelessContext] =
    SimpleBuiltIn(
      "dustAmount",
      Seq.empty,
      Seq[Type](Type.U256),
      U256Const(Val.U256(dustUtxoAmount))
    )

  val panic: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String                  = "panic"
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
  }

  val statelessFuncs: Map[String, FuncInfo[StatelessContext]] = Seq(
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
    /* Below are functions for Leman hard fork */
    byteVecSlice,
    encodeToByteVec,
    zeros,
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
    byteVecToAddress,
    ethEcRecover,
    contractIdToAddress,
    nullAddress,
    dustAmount,
    panic
  ).map(f => f.name -> f).toMap

  val approveAlph: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "approveAlph",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      ApproveAlph,
      isReadonly = false
    )

  val approveToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "approveToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      ApproveToken,
      isReadonly = false
    )

  val alphRemaining: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "alphRemaining",
      Seq(Type.Address),
      Seq(Type.U256),
      AlphRemaining,
      isReadonly = true
    )

  val tokenRemaining: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "tokenRemaining",
      Seq[Type](Type.Address, Type.ByteVec),
      Seq(Type.U256),
      TokenRemaining,
      isReadonly = true
    )

  val isPaying: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("isPaying", Seq(Type.Address), Seq(Type.Bool), IsPaying, isReadonly = true)

  val transferAlph: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "transferAlph",
      Seq[Type](Type.Address, Type.Address, Type.U256),
      Seq.empty,
      TransferAlph,
      isReadonly = false
    )

  val transferAlphFromSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "transferAlphFromSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlphFromSelf,
      useAssetsInContract = true,
      isReadonly = false
    )

  val transferAlphToSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "transferAlphToSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlphToSelf,
      useAssetsInContract = true,
      isReadonly = false
    )

  val transferToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "transferToken",
      Seq[Type](Type.Address, Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferToken,
      isReadonly = false
    )

  val transferTokenFromSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "transferTokenFromSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenFromSelf,
      useAssetsInContract = true,
      isReadonly = false
    )

  val transferTokenToSelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "transferTokenToSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenToSelf,
      useAssetsInContract = true,
      isReadonly = false
    )

  val burnToken: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "burnToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      BurnToken,
      isReadonly = false
    )

  val lockApprovedAssets: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "lockApprovedAssets",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      LockApprovedAssets,
      usePreapprovedAssets = true,
      isReadonly = false
    )

  val createContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "createContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateContract,
      usePreapprovedAssets = true,
      isReadonly = false
    )

  val createContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn[StatefulContext](
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
      isReadonly = false
    )

  val copyCreateContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "copyCreateContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateContract,
      usePreapprovedAssets = true,
      isReadonly = false
    )

  val copyCreateContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn(
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
      isReadonly = false
    )

  val createSubContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "createSubContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateSubContract,
      usePreapprovedAssets = true,
      isReadonly = false
    )

  val createSubContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn(
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
      isReadonly = false
    )

  val copyCreateSubContract: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "copyCreateSubContract",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateSubContract,
      usePreapprovedAssets = true,
      isReadonly = false
    )

  val copyCreateSubContractWithToken: BuiltIn[StatefulContext] =
    OverloadedSimpleBuiltIn(
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
      isReadonly = false
    )

  val destroySelf: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "destroySelf",
      Seq[Type](Type.Address),
      Seq.empty,
      DestroySelf,
      useAssetsInContract = true,
      isReadonly = false
    )

  val migrate: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "migrate",
      Seq[Type](Type.ByteVec),
      Seq.empty,
      MigrateSimple,
      isReadonly = false
    )

  val migrateWithFields: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "migrateWithFields",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq.empty,
      MigrateWithFields,
      isReadonly = false
    )

  val contractExists: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "contractExists",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Bool),
      ContractExists
    )

  val selfAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("selfAddress", Seq.empty, Seq(Type.Address), SelfAddress)

  val selfContractId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("selfContractId", Seq.empty, Seq(Type.ByteVec), SelfContractId)

  val selfTokenId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("selfTokenId", Seq.empty, Seq(Type.ByteVec), SelfContractId)

  val callerContractId: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("callerContractId", Seq.empty, Seq(Type.ByteVec), CallerContractId)

  val callerAddress: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("callerAddress", Seq.empty, Seq(Type.Address), CallerAddress)

  val isCalledFromTxScript: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn("isCalledFromTxScript", Seq.empty, Seq(Type.Bool), IsCalledFromTxScript)

  val callerInitialStateHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "callerInitialStateHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerInitialStateHash
    )

  val callerCodeHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "callerCodeHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerCodeHash
    )

  val contractInitialStateHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "contractInitialStateHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractInitialStateHash
    )

  val contractCodeHash: SimpleBuiltIn[StatefulContext] =
    SimpleBuiltIn(
      "contractCodeHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractCodeHash
    )

  sealed abstract private class SubContractBuiltIn extends BuiltIn[StatefulContext] {
    def name: String
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
  }

  val statefulFuncs: Map[String, FuncInfo[StatefulContext]] =
    statelessFuncs ++ Seq(
      approveAlph,
      approveToken,
      alphRemaining,
      tokenRemaining,
      isPaying,
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
      contractExists,
      destroySelf,
      migrate,
      migrateWithFields,
      selfAddress,
      selfContractId,
      selfTokenId,
      callerContractId,
      callerAddress,
      isCalledFromTxScript,
      callerInitialStateHash,
      callerCodeHash,
      contractInitialStateHash,
      contractCodeHash,
      subContractId,
      subContractIdOf
    ).map(f => f.name -> f)
}
