package org.alephium.protocol.vm.lang

import scala.collection.mutable

import org.alephium.protocol.vm.Val

object Checker {
  case class Error(message: String) extends Exception(message)

  def expectOneType(ident: Ast.Ident, tpe: Seq[Val.Type]): Val.Type = {
    if (tpe.length == 1) tpe(0)
    else throw Error(s"Try to set types $tpe for varialbe $ident")
  }

  case class VarInfo(tpe: Val.Type, isMutable: Boolean)
  case class Ctx(varTable: mutable.HashMap[String, VarInfo]) {
    def branch(): Ctx = Ctx(varTable.clone())

    def addVariable(ident: Ast.Ident, tpe: Seq[Val.Type], isMutable: Boolean): Unit = {
      addVariable(ident, expectOneType(ident, tpe), isMutable)
    }

    def addVariable(ident: Ast.Ident, tpe: Val.Type, isMutable: Boolean): Unit = {
      val name = ident.name
      if (varTable.contains(name)) throw Error(s"Variables have the same name $name")
      else varTable(name) = VarInfo(tpe, isMutable)
    }

    def getVariable(ident: Ast.Ident): VarInfo = {
      varTable.get(ident.name) match {
        case Some(varInfo) => varInfo
        case None          => throw Error(s"Variable $ident does not exist")
      }
    }

    def getType(ident: Ast.Ident): Val.Type = getVariable(ident).tpe

    def check(ident: Ast.Ident, tpe: Seq[Val.Type]): Unit = {
      check(ident, expectOneType(ident, tpe))
    }

    def check(ident: Ast.Ident, tpe: Val.Type): Unit = {
      val varType = getVariable(ident).tpe
      if (varType != tpe) throw Error(s"Type $tpe is invalid for $ident ($varType)")
    }
  }
  object Ctx {
    def empty: Ctx = Ctx(mutable.HashMap.empty)
  }
}
