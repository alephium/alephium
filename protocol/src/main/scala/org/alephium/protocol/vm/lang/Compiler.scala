package org.alephium.protocol.vm.lang

import scala.collection.{immutable, mutable}

import fastparse.Parsed

import org.alephium.protocol.vm._

object Compiler {
  def compile(input: String): Either[Error, StatelessScript] =
    try {
      fastparse.parse(input, Parser.contract(_)) match {
        case Parsed.Success(contract, _) =>
          val ctx = Ctx.buildFor(contract)
          Right(contract.toIR(ctx))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  trait FuncInfo {
    def name: String
    def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type]
    def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]]
  }

  final case class Error(message: String) extends Exception(message)
  object Error {
    def parse(failure: Parsed.Failure): Error = Error(s"Parser failed: $failure")
  }

  def expectOneType(ident: Ast.Ident, tpe: Seq[Val.Type]): Val.Type = {
    if (tpe.length == 1) tpe(0)
    else throw Error(s"Try to set types $tpe for varialbe $ident")
  }

  final case class VarInfo(tpe: Val.Type, isMutable: Boolean, index: Byte)
  class SimpleFunc(val id: Ast.FuncId,
                   argsType: Seq[Val.Type],
                   val returnType: Seq[Val.Type],
                   index: Byte)
      extends FuncInfo {
    def name: String = id.name

    override def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type] = {
      if (inputType == argsType) returnType
      else throw Error(s"Invalid args type $inputType for builtin func $name")
    }

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }
  }
  object SimpleFunc {
    def from(funcs: Seq[Ast.FuncDef]): Seq[SimpleFunc] = {
      funcs.view.zipWithIndex.map {
        case (func, index) =>
          new SimpleFunc(func.id, func.args.map(_.tpe), func.rtypes, index.toByte)
      }.toSeq
    }
  }

  object Ctx {
    def buildFor(contract: Ast.Contract): Ctx =
      Ctx(mutable.HashMap.empty,
          "",
          0,
          mutable.HashMap.empty,
          contract.funcTable,
          immutable.Map(contract.ident -> contract.funcTable))
  }

  final case class Ctx(
      varTable: mutable.HashMap[String, VarInfo],
      var scope: String,
      var varIndex: Int,
      contractObjects: mutable.HashMap[Ast.Ident, Ast.TypeId],
      funcIdents: immutable.Map[Ast.FuncId, SimpleFunc],
      contractTable: immutable.Map[Ast.TypeId, immutable.Map[Ast.FuncId, SimpleFunc]]) {
    def setFuncScope(funcId: Ast.FuncId): Unit = {
      scope    = funcId.name
      varIndex = 0
      contractObjects.clear()
    }

    def addVariable(ident: Ast.Ident, tpe: Seq[Val.Type], isMutable: Boolean): Unit = {
      addVariable(ident, expectOneType(ident, tpe), isMutable)
    }

    private def scopedName(name: String): String = {
      if (scope == "") name else s"$scope.$name"
    }

    def addVariable(ident: Ast.Ident, tpe: Val.Type, isMutable: Boolean): Unit = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(s"Global variable has the same name as local variable: $name")
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name")
      } else if (varIndex >= 0xFF) {
        throw Error(s"Number of variables more than ${0xFF}")
      } else {
        varTable(sname) = VarInfo(tpe, isMutable, varIndex.toByte)
        varIndex += 1
      }
    }

    def getVariable(ident: Ast.Ident): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      varTable.getOrElse(
        sname,
        varTable.getOrElse(name, throw Error(s"Variable $sname does not exist"))
      )
    }

    def getLocalVars(func: Ast.FuncId): Seq[VarInfo] = {
      varTable.view.filterKeys(_.startsWith(func.name)).values.toSeq.sortBy(_.index)
    }

    def toIR(ident: Ast.Ident): Instr[StatelessContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) StoreField(varInfo.index.toByte)
      else StoreLocal(varInfo.index.toByte)
    }

    def isField(ident: Ast.Ident): Boolean = varTable.contains(ident.name)

    def getType(ident: Ast.Ident): Val.Type = getVariable(ident).tpe

    def getFunc(call: Ast.FuncId): FuncInfo = {
      if (call.isBuiltIn) getBuiltInFunc(call)
      else getNewFunc(call)
    }

    def addContractObject(id: Ast.Ident, tpe: Ast.TypeId): Unit = {
      if (contractObjects.contains(id)) {
        throw Error(s"Contracts have the same name ${id.name}")
      } else {
        contractObjects(id) = tpe
      }
    }

    def getFunc(objId: Ast.Ident, callId: Ast.FuncId): FuncInfo = {
      val contract = contractObjects.getOrElse(
        objId,
        throw Error(s"Contract object ${objId.name} does not exist"))
      contractTable(contract)
        .getOrElse(callId, throw Error(s"Function ${objId.name}.${callId.name} does not exist"))
    }

    private def getBuiltInFunc(call: Ast.FuncId): FuncInfo = {
      BuiltIn.funcs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    private def getNewFunc(call: Ast.FuncId): FuncInfo = {
      funcIdents.getOrElse(call, throw Error(s"Function ${call.name} does not exist"))
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Val.Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Val.Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) throw Error(s"Assign $tpe value to $ident: ${varInfo.tpe}")
      if (!varInfo.isMutable) throw Error(s"Assign value to immutable variable $ident")
    }

    def checkReturn(returnType: Seq[Val.Type]): Unit = {
      val rtype = funcIdents(Ast.FuncId(scope, false)).returnType
      if (returnType != rtype)
        throw Compiler.Error(s"Invalid return types: expected $rtype, got $returnType")
    }
  }
}
