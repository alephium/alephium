package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler.{Error, FuncInfo}
import org.alephium.util.AVector

object BuiltIn {
  sealed trait BuiltIn[Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def name: String

    override def genCode(contract: Ast.Ident): Seq[Instr[StatelessContext]] = {
      throw Compiler.Error(s"Built-in function $name does not belong to contract ${contract.name}")
    }
  }
  final case class SimpleBuiltIn(name: String,
                                 argsType: Seq[Type],
                                 returnType: Seq[Type],
                                 instr: Instr[StatelessContext])
      extends BuiltIn[StatelessContext] {
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) returnType
      else throw Error(s"Invalid args type $inputType for builtin func $name")
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = Seq(instr)
  }
  sealed abstract class GenericBuiltIn(val name: String) extends BuiltIn[StatelessContext]

  val checkEq: GenericBuiltIn = new GenericBuiltIn("checkEq") {
    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (!(inputType.length == 2) || inputType(0) != inputType(1))
        throw Error(s"Invalid args type $inputType for builtin func $name")
      else Seq.empty
    }
    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.Bool        => Seq(CheckEqBool)
        case Type.Byte        => Seq(CheckEqByte)
        case Type.I64         => Seq(CheckEqI64)
        case Type.U64         => Seq(CheckEqU64)
        case Type.I256        => Seq(CheckEqI256)
        case Type.U256        => Seq(CheckEqU256)
        case Type.Byte32      => Seq(CheckEqByte32)
        case Type.BoolVec     => Seq(CheckEqBoolVec)
        case Type.ByteVec     => Seq(CheckEqByteVec)
        case Type.I64Vec      => Seq(CheckEqI64Vec)
        case Type.U64Vec      => Seq(CheckEqU64Vec)
        case Type.I256Vec     => Seq(CheckEqI256Vec)
        case Type.U256Vec     => Seq(CheckEqU256Vec)
        case Type.Byte32Vec   => Seq(CheckEqByte32Vec)
        case _: Type.Contract => Seq(CheckEqByte32)
      }
    }
  }

  val keccak256: SimpleBuiltIn =
    SimpleBuiltIn("keccak256", Seq(Type.Byte32), Seq(Type.Byte32), Keccak256Byte32)
  val checkSignature: SimpleBuiltIn =
    SimpleBuiltIn("checkSignature", Seq(Type.Byte32), Seq(), CheckSignature)

  sealed abstract class ConversionBuiltIn(name: String) extends GenericBuiltIn(name) {
    import ConversionBuiltIn.validTypes

    def toType: Type

    def validate(tpe: Type): Boolean = validTypes.contains(tpe) && (tpe != toType)

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType.length != 1 || !validate(inputType(0))) {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      } else Seq(toType)
    }
  }
  object ConversionBuiltIn {
    val validTypes: AVector[Type] = AVector(Type.Byte, Type.I64, Type.U64, Type.I256, Type.U256)
  }

  val toByte: ConversionBuiltIn = new ConversionBuiltIn("byte") {
    override def toType: Type = Type.Byte

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.I64  => Seq(I64ToByte)
        case Type.U64  => Seq(U64ToByte)
        case Type.I256 => Seq(I256ToByte)
        case Type.U256 => Seq(U256ToByte)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toI64: ConversionBuiltIn = new ConversionBuiltIn("i64") {
    override def toType: Type = Type.I64

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.Byte => Seq(ByteToI64)
        case Type.U64  => Seq(U64ToI64)
        case Type.I256 => Seq(I256ToI64)
        case Type.U256 => Seq(U256ToI64)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toU64: ConversionBuiltIn = new ConversionBuiltIn("u64") {
    override def toType: Type = Type.U64

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.Byte => Seq(ByteToU64)
        case Type.I64  => Seq(I64ToU64)
        case Type.I256 => Seq(I256ToU64)
        case Type.U256 => Seq(U256ToU64)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toI256: ConversionBuiltIn = new ConversionBuiltIn("i256") {
    override def toType: Type = Type.I256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.Byte => Seq(ByteToI256)
        case Type.I64  => Seq(I64ToI256)
        case Type.U64  => Seq(U64ToI256)
        case Type.U256 => Seq(U256ToI256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toU256: ConversionBuiltIn = new ConversionBuiltIn("u256") {
    override def toType: Type = Type.U256

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Type.Byte => Seq(ByteToU256)
        case Type.I64  => Seq(I64ToU256)
        case Type.U64  => Seq(U64ToU256)
        case Type.I256 => Seq(I256ToU256)
        case _         => throw new RuntimeException("Dead branch")
      }
    }
  }

  val funcs: Map[String, FuncInfo[StatelessContext]] = Seq(
    keccak256,
    checkEq,
    checkSignature,
    toByte,
    toI64,
    toU64,
    toI256,
    toU256
  ).map(f => f.name -> f).toMap
}
