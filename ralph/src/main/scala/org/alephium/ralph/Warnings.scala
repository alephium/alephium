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
  val warnings: mutable.ArrayBuffer[Warning] = mutable.ArrayBuffer.empty[Warning]

  def compilerOptions: CompilerOptions

  def getWarnings: AVector[Warning] = AVector.from(warnings)

  def warnUnusedVariables(typeId: Ast.TypeId, unusedVariablesInfo: Seq[(String, Type)]): Unit = {
    val newWarnings = unusedVariablesInfo.sortBy(_._1).map { case (name, typ) =>
      Warning(
        s"Found unused variable in ${typeId.name}: $name",
        typ.sourceIndex
      )
    }
    if (!compilerOptions.ignoreUnusedVariablesWarnings) {
      warnings ++= newWarnings
    }
  }

  def warnUnusedMaps(typeId: Ast.TypeId, unusedMaps: Seq[(String, Type)]): Unit = {
    val newWarnings = unusedMaps.map { case (name, typ) =>
      Warning(
        s"Found unused map in ${typeId.name}: $name",
        typ.sourceIndex
      )
    }

    if (!compilerOptions.ignoreUnusedVariablesWarnings) {
      warnings ++= newWarnings
    }
  }

  def warnUnusedLocalConstants(
      typeId: Ast.TypeId,
      unusedConstants: mutable.ArrayBuffer[(String, Option[SourceIndex])]
  ): Unit = {
    if (!compilerOptions.ignoreUnusedConstantsWarnings) {
      val newWarnings = unusedConstants.map { case (name, sourceIndex) =>
        Warning(
          s"Found unused constant in ${typeId.name}: $name",
          sourceIndex
        )
      }

      warnings ++= newWarnings
    }
  }

  def warnUnusedFields(
      typeId: Ast.TypeId,
      unusedFields: mutable.ArrayBuffer[(String, Type)]
  ): Unit = {
    val newWarnings = unusedFields.sortBy(_._1).map { case (name, typ) =>
      Warning(
        s"Found unused field in ${typeId.name}: $name",
        typ.sourceIndex
      )
    }
    if (!compilerOptions.ignoreUnusedFieldsWarnings) {
      warnings ++= newWarnings
    }
  }

  def warnNoUpdateFieldsCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreUpdateFieldsCheckWarnings) {
      warnings += Warning(
        s"""Function ${Ast.funcName(typeId, funcId)} updates fields. """ +
          s"""Please use "@using(updateFields = true)" for the function.""",
        funcId.sourceIndex
      )
    }
  }

  def warnUnnecessaryUpdateFieldsCheck(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreUpdateFieldsCheckWarnings) {
      warnings += Warning(
        s"Function ${Ast.funcName(typeId, funcId)} does not update fields. " +
          s"""Please remove "@using(updateFields = true)" for the function.""",
        funcId.sourceIndex
      )
    }
  }

  def warnUnusedPrivateFunction(typeId: Ast.TypeId, funcIds: Seq[Ast.FuncId]): Unit = {
    if (!compilerOptions.ignoreUnusedPrivateFunctionsWarnings) {
      val newWarnings = funcIds.sortBy(_.name).map { funcId =>
        Warning(
          s"Found unused private function in ${typeId.name}: ${funcId.name}",
          funcId.sourceIndex
        )
      }

      warnings ++= newWarnings
    }
  }

  def warnCheckExternalCaller(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreCheckExternalCallerWarnings) {
      warnings += Warning(
        Warnings.noCheckExternalCallerMsg(typeId.name, funcId.name),
        funcId.sourceIndex
      )
    }
  }

  def warnPrivateFuncHasCheckExternalCaller(typeId: Ast.TypeId, funcId: Ast.FuncId): Unit = {
    if (!compilerOptions.ignoreCheckExternalCallerWarnings) {
      warnings += Warning(
        s"No need to add the checkExternalCaller annotation to the private function ${Ast
            .funcName(typeId, funcId)}",
        funcId.sourceIndex
      )
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
      warnings += Warning(
        s"The return values of the function ${Ast.funcName(typeId, funcId)} are not used." +
          s" Please add $prefix before the function call to explicitly ignore its return value.",
        funcId.sourceIndex
      )
    }
  }
}

object Warnings {
  def noCheckExternalCallerMsg(typeId: String, funcId: String): String = {
    s"""No external caller check for function "${typeId}.${funcId}". """ +
      s"""Please use "checkCaller!(...)" in the function or its callees, or disable it with "@using(checkExternalCaller = false)"."""
  }

  def unusedGlobalConstants(
      unusedConstants: collection.Seq[(String, Ast.Positioned)]
  ): AVector[Warning] = {
    AVector.from(
      unusedConstants.map { case (name, position) =>
        Warning(
          s"Found unused global constant: $name",
          position.sourceIndex
        )
      }
    )
  }

  def unusedLocalConstants(
      typeId: Ast.TypeId,
      unusedConstants: collection.Seq[(String, Option[SourceIndex])]
  ): AVector[Warning] = {
    AVector.from(
      unusedConstants.sortBy(_._1).map { case (name, sourceIndex) =>
        Warning(
          s"Found unused constant in ${typeId.name}: $name",
          sourceIndex
        )
      }
    )
  }

  def unusedPrivateFunctions(
      typeId: Ast.TypeId,
      unusedFuncs: collection.Seq[(String, Option[SourceIndex])]
  ): AVector[Warning] = {
    AVector.from(
      unusedFuncs.sortBy(_._1).map { case (name, sourceIndex) =>
        Warning(
          s"Found unused private function in ${typeId.name}: $name",
          sourceIndex
        )
      }
    )
  }
}
