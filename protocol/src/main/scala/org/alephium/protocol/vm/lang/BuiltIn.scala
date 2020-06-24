package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler.{Error, FuncInfo}
import org.alephium.util.AVector

object BuiltIn {
  final case class SimpleBuiltIn(name: String,
                                 argsType: Seq[Val.Type],
                                 returnType: Seq[Val.Type],
                                 instr: Instr[StatelessContext])
      extends FuncInfo {
    override def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type] = {
      if (inputType == argsType) returnType
      else throw Error(s"Invalid args type $inputType for builtin func $name")
    }

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = Seq(instr)
  }
  abstract class GenericBuiltIn(val name: String) extends FuncInfo

  val checkEq: GenericBuiltIn = new GenericBuiltIn("checkEq") {
    override def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type] = {
      if (!(inputType.length == 2) || inputType(0) != inputType(1))
        throw Error(s"Invalid args type $inputType for builtin func $name")
      else Seq.empty
    }
    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Val.Bool      => Seq(CheckEqBool)
        case Val.Byte      => Seq(CheckEqByte)
        case Val.I64       => Seq(CheckEqI64)
        case Val.U64       => Seq(CheckEqU64)
        case Val.I256      => Seq(CheckEqI256)
        case Val.U256      => Seq(CheckEqU256)
        case Val.Byte32    => Seq(CheckEqByte32)
        case Val.BoolVec   => Seq(CheckEqBoolVec)
        case Val.ByteVec   => Seq(CheckEqByteVec)
        case Val.I64Vec    => Seq(CheckEqI64Vec)
        case Val.U64Vec    => Seq(CheckEqU64Vec)
        case Val.I256Vec   => Seq(CheckEqI256Vec)
        case Val.U256Vec   => Seq(CheckEqU256Vec)
        case Val.Byte32Vec => Seq(CheckEqByte32Vec)
      }
    }
  }

  val keccak256: SimpleBuiltIn =
    SimpleBuiltIn("keccak256", Seq(Val.Byte32), Seq(Val.Byte32), Keccak256Byte32)
  val checkSignature: SimpleBuiltIn =
    SimpleBuiltIn("checkSignature", Seq(Val.Byte32), Seq(), CheckSignature)

  abstract class ConversionBuiltIn(name: String) extends GenericBuiltIn(name) {
    import ConversionBuiltIn.validTypes

    def toType: Val.Type

    def validate(tpe: Val.Type): Boolean = validTypes.contains(tpe) && (tpe != toType)

    override def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type] = {
      if (inputType.length != 1 || !validate(inputType(0))) {
        throw Error(s"Invalid args type $inputType for builtin func $name")
      } else Seq(toType)
    }
  }
  object ConversionBuiltIn {
    val validTypes: AVector[Val.Type] = AVector(Val.Byte, Val.I64, Val.U64, Val.I256, Val.U256)
  }

  val toByte: ConversionBuiltIn = new ConversionBuiltIn("byte") {
    override def toType: Val.Type = Val.Byte

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Val.I64  => Seq(I64ToByte)
        case Val.U64  => Seq(U64ToByte)
        case Val.I256 => Seq(I256ToByte)
        case Val.U256 => Seq(U256ToByte)
        case _        => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toI64: ConversionBuiltIn = new ConversionBuiltIn("i64") {
    override def toType: Val.Type = Val.I64

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Val.Byte => Seq(ByteToI64)
        case Val.U64  => Seq(U64ToI64)
        case Val.I256 => Seq(I256ToI64)
        case Val.U256 => Seq(U256ToI64)
        case _        => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toU64: ConversionBuiltIn = new ConversionBuiltIn("u64") {
    override def toType: Val.Type = Val.U64

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Val.Byte => Seq(ByteToU64)
        case Val.I64  => Seq(I64ToU64)
        case Val.I256 => Seq(I256ToU64)
        case Val.U256 => Seq(U256ToU64)
        case _        => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toI256: ConversionBuiltIn = new ConversionBuiltIn("i256") {
    override def toType: Val.Type = Val.I256

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Val.Byte => Seq(ByteToI256)
        case Val.I64  => Seq(I64ToI256)
        case Val.U64  => Seq(U64ToI256)
        case Val.U256 => Seq(U256ToI256)
        case _        => throw new RuntimeException("Dead branch")
      }
    }
  }
  val toU256: ConversionBuiltIn = new ConversionBuiltIn("u256") {
    override def toType: Val.Type = Val.U256

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      inputType(0) match {
        case Val.Byte => Seq(ByteToU256)
        case Val.I64  => Seq(I64ToU256)
        case Val.U64  => Seq(U64ToU256)
        case Val.I256 => Seq(I256ToU256)
        case _        => throw new RuntimeException("Dead branch")
      }
    }
  }

  val funcs: Map[String, FuncInfo] = Seq(
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
