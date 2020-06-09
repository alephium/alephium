package org.alephium.protocol.vm.lang

import scala.collection.mutable

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Ast.Ident

object Checker {
  case class Error(message: String) extends Exception(message)

  def expectOneType(ident: Ast.Ident, tpe: Seq[Val.Type]): Val.Type = {
    if (tpe.length == 1) tpe(0)
    else throw Error(s"Try to set types $tpe for varialbe $ident")
  }

  case class VarInfo(tpe: Val.Type, isMutable: Boolean, index: Int)
  case class Ctx(varTable: mutable.HashMap[String, VarInfo],
                 var scope: String,
                 var varIndex: Int,
                 funcIdents: mutable.ArrayBuffer[Ast.Ident]) {
    def setScope(ident: Ast.Ident): Unit = {
      if (funcIdents.contains(ident)) throw Error(s"functions have the same name: $ident")

      funcIdents.append(ident)
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
      } else {
        varTable(sname) = VarInfo(tpe, isMutable, varIndex)
        varIndex += 1
      }
    }

    def getVariable(ident: Ast.Ident): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      varTable.get(sname) match {
        case Some(varInfo) => varInfo
        case None =>
          varTable.get(name) match {
            case Some(varInfo) => varInfo
            case None          => throw Error(s"Variable $sname does not exist")
          }
      }
    }

    def getLocalVars(func: Ident): Seq[VarInfo] = {
      varTable.view.filterKeys(_.startsWith(func.name)).values.toSeq.sortBy(_.index)
    }

    def toIR(ident: Ident): Instr[StatelessContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) StoreField(varInfo.index.toByte)
      else StoreLocal(varInfo.index.toByte)
    }

    def isField(ident: Ident): Boolean = varTable.contains(ident.name)

    def getType(ident: Ast.Ident): Val.Type = getVariable(ident).tpe

    def checkAssign(ident: Ast.Ident, tpe: Seq[Val.Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Val.Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) throw Error(s"Type $tpe is invalid for $ident (${varInfo.tpe})")
      if (!varInfo.isMutable) throw Error(s"Assign value to immutable variable $ident")
    }
  }
  object Ctx {
    def empty: Ctx = Ctx(mutable.HashMap.empty, "", 0, mutable.ArrayBuffer.empty)
  }
}
