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

package org.alephium.ralph

import scala.collection.mutable

import org.alephium.util.AVector

trait Warnings {
  val warnings: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
  def compilerOptions: CompilerOptions

  def getWarnings: AVector[String] = AVector.from(warnings)

  def warnUnusedVariables(
      typeId: Ast.TypeId,
      unusedVariables: mutable.HashMap[String, Compiler.VarInfo]
  ): Unit = {
    val unusedVarsString = unusedVariables.keys.toArray.sorted.mkString(", ")
    warnUnusedVariables(typeId, unusedVarsString)
  }
  def warnUnusedVariables(typeId: Ast.TypeId, unusedVariables: String): Unit = {
    if (!compilerOptions.ignoreUnusedVariablesWarnings) {
      warnings += s"Found unused variables in ${typeId.name}: ${unusedVariables}"
    }
  }

  def warnUnusedConstants(
      typeId: Ast.TypeId,
      unusedConstants: mutable.ArrayBuffer[String]
  ): Unit = {
    warnUnusedConstants(typeId, unusedConstants.sorted.mkString(", "))
  }

  def warnUnusedConstants(typeId: Ast.TypeId, unusedConstants: String): Unit = {
    if (!compilerOptions.ignoreUnusedConstantsWarnings) {
      warnings += s"Found unused constants in ${typeId.name}: ${unusedConstants}"
    }
  }

  def warnUnusedFields(typeId: Ast.TypeId, unusedFields: mutable.ArrayBuffer[String]): Unit = {
    warnUnusedFields(typeId, unusedFields.sorted.mkString(", "))
  }

  def warnUnusedFields(typeId: Ast.TypeId, unusedFields: String): Unit = {
    if (!compilerOptions.ignoreUnusedFieldsWarnings) {
      warnings += s"Found unused fields in ${typeId.name}: ${unusedFields}"
    }
  }

  def warnReadonlyCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreReadonlyCheckWarnings) {
      warnings += s"Function ${typeId.name}.${funcId.name} is readonly, please use @using(readonly = true) for the function"
    }
  }

  def warnUnusedPrivateFunction(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreUnusedPrivateFunctionsWarnings) {
      warnings += s"Private function ${typeId.name}.${funcId.name} is not used"
    }
  }

  def warnExternalCallCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreExternalCallCheckWarnings) {
      warnings += Warnings.noExternalCallCheckMsg(typeId.name, funcId.name)
    }
  }

  def warnNonReadonlyAndNoExternalCallCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreReadonlyCheckWarnings) {
      warnings += s"No readonly annotation for function: ${typeId.name}.${funcId.name}, please use @using(readonly = true/false) for the function"
    }
  }
}

object Warnings {
  def noExternalCallCheckMsg(typeId: String, funcId: String): String =
    s"No external call check for function: ${typeId}.${funcId}, please use checkCaller!(...) for the function or its private callees."
}
