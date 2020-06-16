package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler.{Error, FuncInfo}

object BuiltIn {
  case class SimpleBuiltIn(name: String,
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

  val checkEq = new GenericBuiltIn("checkEq") {
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

  val keccak256      = SimpleBuiltIn("keccak256", Seq(Val.Byte32), Seq(Val.Byte32), Keccak256Byte32)
  val checkSignature = SimpleBuiltIn("checkSignature", Seq(Val.Byte32), Seq(), CheckSignature)

  val funcs: Map[String, FuncInfo] = Seq(
    BuiltIn.keccak256,
    BuiltIn.checkEq,
    BuiltIn.checkSignature,
  ).map(f => f.name -> f).toMap
}
