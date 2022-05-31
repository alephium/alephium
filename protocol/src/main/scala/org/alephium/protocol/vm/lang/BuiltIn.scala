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

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler.{Error, FuncInfo}
import org.alephium.util.AVector

object BuiltIn {
  sealed trait BuiltIn[-Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def name: String

    override def isPublic: Boolean = true

    override def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      throw Compiler.Error(s"Built-in function $name does not belong to contract ${typeId.name}")
    }
  }

  sealed trait SimpleBuiltIn[-Ctx <: StatelessContext] extends BuiltIn[Ctx] {
    def argsType: Seq[Type]
    def returnType: Seq[Type]
    def instr: Instr[Ctx]

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]] = Seq(instr)
  }

  final case class SimpleStatelessBuiltIn(
      name: String,
      argsType: Seq[Type],
      returnType: Seq[Type],
      instr: Instr[StatelessContext]
  ) extends SimpleBuiltIn[StatelessContext]

  final case class SimpleStatefulBuiltIn(
      name: String,
      argsType: Seq[Type],
      returnType: Seq[Type],
      instr: Instr[StatefulContext]
  ) extends SimpleBuiltIn[StatefulContext]

  sealed abstract class GenericStatelessBuiltIn(val name: String) extends BuiltIn[StatelessContext]

  val blake2b: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("blake2b", Seq(Type.ByteVec), Seq(Type.ByteVec), Blake2b)
  val keccak256: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("keccak256", Seq(Type.ByteVec), Seq(Type.ByteVec), Keccak256)
  val sha256: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("sha256", Seq(Type.ByteVec), Seq(Type.ByteVec), Sha256)
  val sha3: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("sha3", Seq(Type.ByteVec), Seq(Type.ByteVec), Sha3)
  val assert: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("assert", Seq(Type.Bool), Seq(), Assert)
  val verifyTxSignature: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("verifyTxSignature", Seq(Type.ByteVec), Seq(), VerifyTxSignature)
  val verifySecP256K1: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "verifySecP256K1",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifySecP256K1
    )
  val verifyED25519: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "verifyED25519",
      Seq(Type.ByteVec, Type.ByteVec, Type.ByteVec),
      Seq.empty,
      VerifyED25519
    )
  val ethEcRecover: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "ethEcRecover",
      Seq(Type.ByteVec, Type.ByteVec),
      Seq(Type.ByteVec),
      EthEcRecover
    )
  val networkId: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("networkId", Seq.empty, Seq(Type.ByteVec), NetworkId)
  val blockTimeStamp: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("blockTimeStamp", Seq.empty, Seq(Type.U256), BlockTimeStamp)
  val blockTarget: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("blockTarget", Seq.empty, Seq(Type.U256), BlockTarget)
  val txId: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("txId", Seq.empty, Seq(Type.ByteVec), TxId)
  val txCaller: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("txCaller", Seq(Type.U256), Seq(Type.Address), TxCaller)
  val txCallerSize: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("txCallerSize", Seq.empty, Seq(Type.U256), TxCallerSize)
  val verifyAbsoluteLocktime: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "verifyAbsoluteLocktime",
      Seq(Type.U256),
      Seq.empty,
      VerifyAbsoluteLocktime
    )
  val verifyRelativeLocktime: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
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

  val toI256: ConversionBuiltIn = new ConversionBuiltIn("i256") {
    val validTypes: AVector[Type] = AVector(Type.U256)

    override def toType: Type = Type.I256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.U256 => Seq(U256ToI256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toU256: ConversionBuiltIn = new ConversionBuiltIn("u256") {
    val validTypes: AVector[Type] = AVector(Type.I256)

    override def toType: Type = Type.U256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.I256 => Seq(I256ToU256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }

  val toByteVec: ConversionBuiltIn = new ConversionBuiltIn("byteVec") {
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

  val size: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("size", Seq[Type](Type.ByteVec), Seq[Type](Type.U256), ByteVecSize)

  val isAssetAddress: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "isAssetAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsAssetAddress
    )

  val isContractAddress: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "isContractAddress",
      Seq[Type](Type.Address),
      Seq[Type](Type.Bool),
      IsContractAddress
    )

  val byteVecSlice: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "byteVecSlice",
      Seq[Type](Type.ByteVec, Type.U256, Type.U256),
      Seq[Type](Type.ByteVec),
      ByteVecSlice
    )

  val encodeToByteVec: BuiltIn[StatelessContext] = new BuiltIn[StatelessContext] {
    val name: String = "encodeToByteVec"

    override def isVariadic: Boolean = true

    def getReturnType(inputType: Seq[Type]): Seq[Type] = Seq(Type.ByteVec)

    def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(Encode)
  }

  val zeros: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "zeros",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      Zeros
    )

  val u256To1Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256To1Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To1Byte
    )

  val u256To2Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256To2Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To2Byte
    )

  val u256To4Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256To4Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To4Byte
    )

  val u256To8Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256To8Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To8Byte
    )

  val u256To16Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256To16Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To16Byte
    )

  val u256To32Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256To32Byte",
      Seq[Type](Type.U256),
      Seq[Type](Type.ByteVec),
      U256To32Byte
    )

  val u256From1Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256From1Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From1Byte
    )

  val u256From2Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256From2Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From2Byte
    )

  val u256From4Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256From4Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From4Byte
    )

  val u256From8Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256From8Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From8Byte
    )

  val u256From16Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256From16Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From16Byte
    )

  val u256From32Byte: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "u256From32Byte",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.U256),
      U256From32Byte
    )

  val byteVecToAddress: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "byteVecToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ByteVecToAddress
    )

  val contractIdToAddress: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn(
      "contractIdToAddress",
      Seq[Type](Type.ByteVec),
      Seq[Type](Type.Address),
      ContractIdToAddress
    )

  val statelessFuncs: Map[String, FuncInfo[StatelessContext]] = Seq(
    blake2b,
    keccak256,
    sha256,
    sha3,
    assert,
    verifyTxSignature,
    verifySecP256K1,
    verifyED25519,
    networkId,
    blockTimeStamp,
    blockTarget,
    txId,
    txCaller,
    txCallerSize,
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
    contractIdToAddress
  ).map(f => f.name -> f).toMap

  val approveAlph: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("approveAlph", Seq[Type](Type.Address, Type.U256), Seq.empty, ApproveAlph)

  val approveToken: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "approveToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      ApproveToken
    )

  val alphRemaining: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("alphRemaining", Seq(Type.Address), Seq(Type.U256), AlphRemaining)

  val tokenRemaining: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "tokenRemaining",
      Seq[Type](Type.Address, Type.ByteVec),
      Seq(Type.U256),
      TokenRemaining
    )

  val isPaying: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("isPaying", Seq(Type.Address), Seq(Type.Bool), IsPaying)

  val transferAlph: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferAlph",
      Seq[Type](Type.Address, Type.Address, Type.U256),
      Seq.empty,
      TransferAlph
    )

  val transferAlphFromSelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferAlphFromSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlphFromSelf
    )

  val transferAlphToSelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferAlphToSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlphToSelf
    )

  val transferToken: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferToken",
      Seq[Type](Type.Address, Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferToken
    )

  val transferTokenFromSelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferTokenFromSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenFromSelf
    )

  val transferTokenToSelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferTokenToSelf",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      TransferTokenToSelf
    )

  val createContract: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "createContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CreateContract
    )

  val createContractWithToken: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "createContractWithToken",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.U256),
      Seq[Type](Type.ByteVec),
      CreateContractWithToken
    )

  val copyCreateContract: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "copyCreateContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq[Type](Type.ByteVec),
      CopyCreateContract
    )

  val copyCreateContractWithToken: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "copyCreateContractWithToken",
      Seq[Type](Type.ByteVec, Type.ByteVec, Type.U256),
      Seq[Type](Type.ByteVec),
      CopyCreateContractWithToken
    )

  val destroySelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "destroySelf",
      Seq[Type](Type.Address),
      Seq.empty,
      DestroySelf
    )

  val migrate: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "migrate",
      Seq[Type](Type.ByteVec),
      Seq.empty,
      MigrateSimple
    )

  val migrateWithFields: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "migrateWithFields",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq.empty,
      MigrateWithFields
    )

  val selfAddress: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("selfAddress", Seq.empty, Seq(Type.Address), SelfAddress)

  val selfContractId: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("selfContractId", Seq.empty, Seq(Type.ByteVec), SelfContractId)

  val selfTokenId: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("selfTokenId", Seq.empty, Seq(Type.ByteVec), SelfContractId)

  val callerContractId: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("callerContractId", Seq.empty, Seq(Type.ByteVec), CallerContractId)

  val callerAddress: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("callerAddress", Seq.empty, Seq(Type.Address), CallerAddress)

  val isCalledFromTxScript: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("isCalledFromTxScript", Seq.empty, Seq(Type.Bool), IsCalledFromTxScript)

  val callerInitialStateHash: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "callerInitialStateHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerInitialStateHash
    )

  val callerCodeHash: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "callerCodeHash",
      Seq.empty,
      Seq(Type.ByteVec),
      CallerCodeHash
    )

  val contractInitialStateHash: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "contractInitialStateHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractInitialStateHash
    )

  val contractCodeHash: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "contractCodeHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractCodeHash
    )

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
      createContract,
      createContractWithToken,
      copyCreateContract,
      copyCreateContractWithToken,
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
      contractCodeHash
    ).map(f => f.name -> f)
}
