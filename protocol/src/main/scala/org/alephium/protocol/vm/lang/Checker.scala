package org.alephium.protocol.vm.lang

import scala.collection.mutable

import org.alephium.protocol.vm._

object Checker {
  trait FuncInfo {
    def name: String
    def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type]
    def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]]
  }

  final case class Error(message: String) extends Exception(message)

  def expectOneType(ident: Ast.Ident, tpe: Seq[Val.Type]): Val.Type = {
    if (tpe.length == 1) tpe(0)
    else throw Error(s"Try to set types $tpe for varialbe $ident")
  }

  final case class VarInfo(tpe: Val.Type, isMutable: Boolean, index: Byte)
  class SimpleFunc(val name: String,
                   argsType: Seq[Val.Type],
                   val returnType: Seq[Val.Type],
                   index: Byte)
      extends FuncInfo {
    override def getReturnType(inputType: Seq[Val.Type]): Seq[Val.Type] = {
      if (inputType == argsType) returnType
      else throw Error(s"Invalid args type $inputType for builtin func $name")
    }

    override def toIR(inputType: Seq[Val.Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }
  }

  final case class Ctx(varTable: mutable.HashMap[String, VarInfo],
                       var scope: String,
                       var varIndex: Int,
                       funcIdents: mutable.HashMap[String, SimpleFunc]) {
    def setFuncScope(ident: Ast.Ident): Unit = {
      scope    = ident.name
      varIndex = 0
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

    def getLocalVars(func: Ast.Ident): Seq[VarInfo] = {
      varTable.view.filterKeys(_.startsWith(func.name)).values.toSeq.sortBy(_.index)
    }

    def toIR(ident: Ast.Ident): Instr[StatelessContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) StoreField(varInfo.index.toByte)
      else StoreLocal(varInfo.index.toByte)
    }

    def isField(ident: Ast.Ident): Boolean = varTable.contains(ident.name)

    def getType(ident: Ast.Ident): Val.Type = getVariable(ident).tpe

    def addFuncDefs(funcs: Seq[Ast.FuncDef]): Unit = {
      if (funcs.length > 0xFF) throw Error(s"Number of functions are greater than ${funcs.length}")
      funcs.zipWithIndex.foreach {
        case (func, index) => addFuncDef(func, index.toByte)
      }
    }

    def addFuncDef(func: Ast.FuncDef, index: Byte): Unit = {
      val name = func.ident.name
      if (funcIdents.contains(name)) {
        throw Error(s"Functions have the same name $name")
      } else {
        funcIdents(name) = new SimpleFunc(name, func.args.map(_.tpe), func.rtypes, index)
      }
    }

    def getFunc(call: Ast.CallId): FuncInfo = {
      if (call.isBuiltIn) getBuiltInFunc(call)
      else getNewFunc(call)
    }

    private def getBuiltInFunc(call: Ast.CallId): FuncInfo = {
      BuiltIn.funcs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    private def getNewFunc(call: Ast.CallId): FuncInfo = {
      funcIdents.getOrElse(call.name, throw Error(s"Function ${call.name} does not exist"))
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
      val rtype = funcIdents(scope).returnType
      if (returnType != rtype)
        throw Checker.Error(s"Invalid return types: expected $rtype, got $returnType")
    }
  }
  object Ctx {
    def empty: Ctx = Ctx(mutable.HashMap.empty, "", 0, mutable.HashMap.empty)
  }
}
