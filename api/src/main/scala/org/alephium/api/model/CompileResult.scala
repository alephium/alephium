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

package org.alephium.api.model

import scala.jdk.CollectionConverters.IteratorHasAsScala

import org.alephium.protocol.Hash
import org.alephium.protocol.model.ReleaseVersion
import org.alephium.protocol.vm
import org.alephium.protocol.vm.StatefulContext
import org.alephium.ralph.{Ast, CompiledContract, CompiledScript, Warning => CompilerWarning}
import org.alephium.serde.serialize
import org.alephium.util.{AVector, DiffMatchPatch, Hex}

final case class CompileScriptResult(
    version: String,
    name: String,
    bytecodeTemplate: String,
    bytecodeDebugPatch: CompileProjectResult.Patch,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    warnings: AVector[String]
) extends CompileResult.Versioned

object CompileScriptResult {
  def from(compiled: CompiledScript): CompileScriptResult = {
    val bytecodeTemplate      = compiled.code.toTemplateString()
    val bytecodeDebugTemplate = compiled.debugCode.toTemplateString()
    val scriptAst             = compiled.ast
    val fields = CompileResult.FieldsSig(
      scriptAst.getTemplateVarsNames(),
      scriptAst.getTemplateVarsTypes(),
      scriptAst.getTemplateVarsMutability()
    )
    CompileScriptResult(
      ReleaseVersion.current.toString(),
      scriptAst.name,
      bytecodeTemplate,
      CompileProjectResult.diffPatch(bytecodeTemplate, bytecodeDebugTemplate),
      fields = fields,
      functions = AVector.from(scriptAst.orderedFuncs.view.map(CompileResult.FunctionSig.from)),
      warnings = compiled.warnings.map(_.message)
    )
  }
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class CompileContractResult(
    version: String,
    name: String,
    bytecode: String,
    bytecodeDebugPatch: CompileProjectResult.Patch,
    codeHash: Hash,
    codeHashDebug: Hash,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    constants: AVector[CompileResult.Constant],
    enums: AVector[CompileResult.Enum],
    events: AVector[CompileResult.EventSig],
    warnings: AVector[String],
    maps: Option[CompileResult.MapsSig] = None,
    stdInterfaceId: Option[String] = None
) extends CompileResult.Versioned

object CompileContractResult {
  def from(compiled: CompiledContract): CompileContractResult = {
    val contractAst = compiled.ast
    assume(contractAst.templateVars.isEmpty) // Template variable is disabled right now
    val bytecode      = Hex.toHexString(serialize(compiled.code))
    val debugBytecode = Hex.toHexString(serialize(compiled.debugCode))
    val fields = CompileResult.FieldsSig(
      contractAst.getFieldNames(),
      contractAst.getFieldTypes(),
      contractAst.getFieldMutability()
    )
    CompileContractResult(
      ReleaseVersion.current.toString(),
      contractAst.name,
      bytecode,
      CompileProjectResult.diffPatch(bytecode, debugBytecode),
      compiled.code.hash,
      compiled.debugCode.hash,
      fields,
      functions = AVector.from(contractAst.orderedFuncs.view.map(CompileResult.FunctionSig.from)),
      maps = CompileResult.MapsSig.from(contractAst.maps),
      events = AVector.from(contractAst.events.map(CompileResult.EventSig.from)),
      constants =
        AVector.from(contractAst.getCalculatedConstants().map(CompileResult.Constant.from.tupled)),
      enums = AVector.from(contractAst.enums.map(CompileResult.Enum.from)),
      warnings = compiled.warnings.map(_.message),
      stdInterfaceId = if (contractAst.hasStdIdField) {
        contractAst.stdInterfaceId.map(id =>
          Hex.toHexString(id.bytes.drop(Ast.StdInterfaceIdPrefix.length))
        )
      } else {
        None
      }
    )
  }
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class CompileProjectResult(
    contracts: AVector[CompileContractResult],
    scripts: AVector[CompileScriptResult],
    structs: Option[AVector[CompileResult.StructSig]] = None,
    constants: Option[AVector[CompileResult.Constant]] = None,
    enums: Option[AVector[CompileResult.Enum]] = None,
    warnings: Option[AVector[String]] = None
)

object CompileProjectResult {
  def from(
      contracts: AVector[CompiledContract],
      scripts: AVector[CompiledScript],
      globalState: Ast.GlobalState[StatefulContext],
      warnings: AVector[CompilerWarning]
  ): CompileProjectResult = {
    val compiledContracts = contracts.map(c => CompileContractResult.from(c))
    val compiledScripts   = scripts.map(s => CompileScriptResult.from(s))
    val structs           = AVector.from(globalState.structs)
    val constants         = AVector.from(globalState.getCalculatedConstants())
    val enums             = AVector.from(globalState.enums)
    CompileProjectResult(
      compiledContracts,
      compiledScripts,
      Option.when(structs.nonEmpty)(structs.map(CompileResult.StructSig.from)),
      Option.when(constants.nonEmpty)(constants.map(CompileResult.Constant.from.tupled)),
      Option.when(enums.nonEmpty)(enums.map(CompileResult.Enum.from)),
      Option.when(warnings.nonEmpty)(warnings.map(_.message))
    )
  }

  final case class Patch(value: String) extends AnyVal

  def diffPatch(code: String, debugCode: String): Patch = {
    val diffs = new DiffMatchPatch().diff_main(code, debugCode)
    if (diffs.size() == 1 && diffs.get(0).operation == DiffMatchPatch.Operation.EQUAL) {
      // both are equal, no need to patch
      Patch("")
    } else {
      val diffsConverted = diffs.iterator().asScala.map { diff =>
        diff.operation match {
          case DiffMatchPatch.Operation.EQUAL  => s"=${diff.text.length}"
          case DiffMatchPatch.Operation.DELETE => s"-${diff.text.length}"
          case DiffMatchPatch.Operation.INSERT => s"+${diff.text}"
        }
      }
      Patch(diffsConverted.mkString(""))
    }
  }

  def applyPatchUnsafe(code: String, patch: Patch): String = {
    val pattern   = "[=+-][0-9a-f]*".r
    var index     = 0
    val debugCode = new StringBuilder()
    pattern.findAllIn(patch.value).foreach { part =>
      part(0) match {
        case '=' =>
          val length = part.tail.toInt
          debugCode ++= (code.slice(index, index + length))
          index = index + length
        case '+' =>
          debugCode ++= (part.tail)
        case '-' =>
          index = index + part.tail.toInt
      }
    }
    debugCode.result()
  }
}

object CompileResult {
  trait Versioned {
    def version: String
  }

  final case class FieldsSig(
      names: AVector[String],
      types: AVector[String],
      isMutable: AVector[Boolean]
  )

  final case class FunctionSig(
      name: String,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      isPublic: Boolean,
      paramNames: AVector[String],
      paramTypes: AVector[String],
      paramIsMutable: AVector[Boolean],
      returnTypes: AVector[String]
  )
  object FunctionSig {
    def from(func: Ast.FuncDef[StatefulContext]): FunctionSig = {
      FunctionSig(
        func.id.name,
        func.usePreapprovedAssets,
        func.useAssetsInContract != Ast.NotUseContractAssets,
        func.isPublic,
        func.getArgNames(),
        func.getArgTypeSignatures(),
        func.getArgMutability(),
        func.getReturnSignatures()
      )
    }
  }

  final case class Constant(name: String, value: Val)
  object Constant {
    def from(ident: Ast.Ident, value: vm.Val): Constant = {
      Constant(ident.name, Val.from(value))
    }
  }

  final case class EnumField(name: String, value: Val)
  object EnumField {
    def from(enumFieldDef: Ast.EnumField[StatefulContext]): EnumField = {
      EnumField(enumFieldDef.name, Val.from(enumFieldDef.value.v))
    }
  }

  final case class Enum(name: String, fields: AVector[EnumField])
  object Enum {
    def from(enumDef: Ast.EnumDef[StatefulContext]): Enum = {
      Enum(enumDef.name, AVector.from(enumDef.fields.map(EnumField.from)))
    }
  }

  final case class MapsSig(names: AVector[String], types: AVector[String])
  object MapsSig {
    def from(mapDefs: Seq[Ast.MapDef]): Option[MapsSig] = {
      Option.when(mapDefs.nonEmpty)(
        MapsSig(
          AVector.from(mapDefs.view.map(_.name)),
          AVector.from(mapDefs.view.map(_.tpe.signature))
        )
      )
    }
  }

  final case class EventSig(
      name: String,
      fieldNames: AVector[String],
      fieldTypes: AVector[String]
  )
  object EventSig {
    def from(event: Ast.EventDef): EventSig = {
      EventSig(
        event.name,
        event.getFieldNames(),
        event.getFieldTypeSignatures()
      )
    }
  }

  final case class StructSig(
      name: String,
      fieldNames: AVector[String],
      fieldTypes: AVector[String],
      isMutable: AVector[Boolean]
  )
  object StructSig {
    def from(struct: Ast.Struct): StructSig = {
      StructSig(
        struct.id.name,
        struct.getFieldNames(),
        struct.getFieldTypeSignatures(),
        struct.getFieldsMutability()
      )
    }
  }
}
