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

  def warnUnusedMaps(typeId: Ast.TypeId, unusedMaps: Seq[String]): Unit = {
    if (!compilerOptions.ignoreUnusedVariablesWarnings) {
      warnings += s"Found unused maps in ${typeId.name}: ${unusedMaps.sorted.mkString(", ")}"
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

  def warnNoUpdateFieldsCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreUpdateFieldsCheckWarnings) {
      warnings += s"""Function ${Ast.funcName(typeId, funcId)} updates fields. """ +
        s"""Please use "@using(updateFields = true)" for the function."""
    }
  }

  def warnUnnecessaryUpdateFieldsCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreUpdateFieldsCheckWarnings) {
      warnings += s"Function ${Ast.funcName(typeId, funcId)} does not update fields. " +
        s"""Please remove "@using(updateFields = true)" for the function."""
    }
  }

  def warnUnusedPrivateFunction(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreUnusedPrivateFunctionsWarnings) {
      warnings += s"Private function ${Ast.funcName(typeId, funcId)} is not used"
    }
  }

  def warnCheckExternalCaller(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreCheckExternalCallerWarnings) {
      warnings += Warnings.noCheckExternalCallerMsg(typeId.name, funcId.name)
    }
  }

  def warnPrivateFuncHasCheckExternalCaller(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreCheckExternalCallerWarnings) {
      warnings += s"No need to add the checkExternalCaller annotation to the private function ${Ast
          .funcName(typeId, funcId)}"
    }
  }

  def warningUnusedCallReturn(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    warnings += s"The return values of the function ${Ast.funcName(typeId, funcId)} are not used." +
      s" If this is intentional, consider using anonymous variables to suppress this warning."
  }

  def warningMutableStructField(
      typeId: Ast.TypeId,
      fieldId: Ast.Ident,
      structId: Ast.TypeId
  ): Unit = {
    warnings +=
      s"The struct ${structId.name} is immutable, you can remove the `mut` from ${typeId.name}.${fieldId.name}"
  }
}

object Warnings {
  def noCheckExternalCallerMsg(typeId: String, funcId: String): String = {
    s"""No external caller check for function "${typeId}.${funcId}". """ +
      s"""Please use "checkCaller!(...)" in the function or its callees, or disable it with "@using(checkExternalCaller = false)"."""
  }
}
