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

  def warnUnusedVariables(typeId: Ast.TypeId, unusedVariables: Seq[String]): Unit = {
    val unusedVarsString = unusedVariables.sorted.mkString(", ")
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

  def warnUnusedLocalConstants(
      typeId: Ast.TypeId,
      unusedConstants: mutable.ArrayBuffer[String]
  ): Unit = {
    if (!compilerOptions.ignoreUnusedConstantsWarnings) {
      warnings += Warnings.unusedLocalConstants(typeId, unusedConstants)
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

  def warnUnusedPrivateFunction(typeId: Ast.TypeId, funcIds: Seq[String]): Unit = {
    if (!compilerOptions.ignoreUnusedPrivateFunctionsWarnings) {
      warnings += Warnings.unusedPrivateFunctions(typeId, funcIds)
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

  def warningUnusedCallReturn(typeId: Ast.TypeId, funcId: Ast.FuncId, retSize: Int): Unit = {
    assume(retSize > 0)
    if (!compilerOptions.ignoreUnusedFunctionReturnWarnings) {
      val prefix = if (retSize == 1) {
        "`let _ = `"
      } else {
        s"`let ${Seq.fill(retSize)("_").mkString("(", ", ", ")")} = `"
      }
      warnings += s"The return values of the function ${Ast.funcName(typeId, funcId)} are not used." +
        s" Please add $prefix before the function call to explicitly ignore its return value."
    }
  }
}

object Warnings {
  def noCheckExternalCallerMsg(typeId: String, funcId: String): String = {
    s"""No external caller check for function "${typeId}.${funcId}". """ +
      s"""Please use "checkCaller!(...)" in the function or its callees, or disable it with "@using(checkExternalCaller = false)"."""
  }

  def unusedGlobalConstants(names: Seq[String]): String = {
    s"Found unused global constants: ${names.sorted.mkString(", ")}"
  }

  def unusedLocalConstants(typeId: Ast.TypeId, unusedConstants: collection.Seq[String]): String = {
    s"Found unused constants in ${typeId.name}: ${unusedConstants.sorted.mkString(", ")}"
  }

  def unusedPrivateFunctions(typeId: Ast.TypeId, unusedFuncs: collection.Seq[String]): String = {
    s"Found unused private functions in ${typeId.name}: ${unusedFuncs.sorted.mkString(", ")}"
  }
}
