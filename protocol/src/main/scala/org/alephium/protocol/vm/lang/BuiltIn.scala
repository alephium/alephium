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
  val checkSignature: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("checkSignature", Seq(Type.ByteVec), Seq(), CheckSignature)
  val blockTimeStamp: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("blockTimeStamp", Seq.empty, Seq(Type.U256), BlockTimeStamp)
  val blockTarget: SimpleStatelessBuiltIn =
    SimpleStatelessBuiltIn("blockTarget", Seq.empty, Seq(Type.U256), BlockTarget)

  sealed abstract class ConversionBuiltIn(name: String) extends GenericStatelessBuiltIn(name) {
    import ConversionBuiltIn.validTypes

    def toType: Type

    def validate(tpe: Type): Boolean = validTypes.contains(tpe) && (tpe != toType)

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType.length != 1 || !validate(inputType(0))) {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      } else {
        Seq(toType)
      }
    }
  }
  object ConversionBuiltIn {
    val validTypes: AVector[Type] = AVector(Type.I256, Type.U256)
  }

  val toI256: ConversionBuiltIn = new ConversionBuiltIn("i256") {
    override def toType: Type = Type.I256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.U256 => Seq(U256ToI256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toU256: ConversionBuiltIn = new ConversionBuiltIn("u256") {
    override def toType: Type = Type.U256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.I256 => Seq(I256ToU256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }

  val statelessFuncs: Map[String, FuncInfo[StatelessContext]] = Seq(
    blake2b,
    keccak256,
    sha256,
    sha3,
    assert,
    checkSignature,
    blockTimeStamp,
    blockTarget,
    toI256,
    toU256
  ).map(f => f.name -> f).toMap

  val approveAlf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("approveAlf", Seq[Type](Type.Address, Type.U256), Seq.empty, ApproveAlf)

  val approveToken: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "approveToken",
      Seq[Type](Type.Address, Type.ByteVec, Type.U256),
      Seq.empty,
      ApproveToken
    )

  val alfRemaining: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("alfRemaining", Seq(Type.Address), Seq(Type.U256), AlfRemaining)

  val tokenRemaining: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "tokenRemaining",
      Seq[Type](Type.Address, Type.ByteVec),
      Seq(Type.U256),
      TokenRemaining
    )

  val transferAlf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferAlf",
      Seq[Type](Type.Address, Type.Address, Type.U256),
      Seq.empty,
      TransferAlf
    )

  val transferAlfFromSelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferAlfFromSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlfFromSelf
    )

  val transferAlfToSelf: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "transferAlfToSelf",
      Seq[Type](Type.Address, Type.U256),
      Seq.empty,
      TransferAlfToSelf
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
      Seq.empty,
      CreateContract
    )

  val copyCreateContract: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "copyCreateContract",
      Seq[Type](Type.ByteVec, Type.ByteVec),
      Seq.empty,
      CopyCreateContract
    )

  val destroyContract: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "destroyContract",
      Seq[Type](Type.ByteVec, Type.Address),
      Seq.empty,
      DestroyContract
    )

  val selfAddress: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("selfAddress", Seq.empty, Seq(Type.Address), SelfAddress)

  val selfContractId: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("selfContractId", Seq.empty, Seq(Type.ByteVec), SelfContractId)

  val selfTokenId: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("selfTokenId", Seq.empty, Seq(Type.ByteVec), SelfContractId)

  val issueToken: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("issueToken", Seq(Type.U256), Seq.empty, IssueToken)

  val callerAddress: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("callerAddress", Seq.empty, Seq(Type.Address), CallerAddress)

  val callerCodeHash: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn("callerCodeHash", Seq.empty, Seq(Type.ByteVec), CallerCodeHash)

  val contractCodeHash: SimpleStatefulBuiltIn =
    SimpleStatefulBuiltIn(
      "contractCodeHash",
      Seq(Type.ByteVec),
      Seq(Type.ByteVec),
      ContractCodeHash
    )

  val statefulFuncs: Map[String, FuncInfo[StatefulContext]] =
    statelessFuncs ++ Seq(
      approveAlf,
      approveToken,
      alfRemaining,
      tokenRemaining,
      transferAlf,
      transferAlfFromSelf,
      transferAlfToSelf,
      transferToken,
      transferTokenFromSelf,
      transferTokenToSelf,
      createContract,
      copyCreateContract,
      destroyContract,
      selfAddress,
      selfContractId,
      selfTokenId,
      issueToken,
      callerAddress,
      callerCodeHash,
      contractCodeHash
    ).map(f => f.name -> f)
}
