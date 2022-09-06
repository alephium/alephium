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

import org.alephium.protocol.Hash
import org.alephium.protocol.vm.{StatefulContext, StatefulContract, StatefulScript}
import org.alephium.protocol.vm.lang.Ast
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex}

final case class CompileScriptResult(
    name: String,
    bytecodeTemplate: String,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    warnings: AVector[String]
)

object CompileScriptResult {
  def from(
      script: StatefulScript,
      scriptAst: Ast.TxScript,
      warnings: AVector[String]
  ): CompileScriptResult = {
    val bytecodeTemplate = script.toTemplateString()
    val fields = CompileResult.FieldsSig(
      scriptAst.getTemplateVarsNames(),
      scriptAst.getTemplateVarsTypes(),
      scriptAst.getTemplateVarsMutability()
    )
    CompileScriptResult(
      scriptAst.name,
      bytecodeTemplate,
      fields = fields,
      functions = AVector.from(scriptAst.funcs.view.map(CompileResult.FunctionSig.from)),
      warnings = warnings
    )
  }
}

final case class CompileContractResult(
    name: String,
    bytecode: String,
    codeHash: Hash,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    events: AVector[CompileResult.EventSig],
    warnings: AVector[String]
)

object CompileContractResult {
  def from(
      contract: StatefulContract,
      contractAst: Ast.Contract,
      warnings: AVector[String]
  ): CompileContractResult = {
    assume(contractAst.templateVars.isEmpty) // Template variable is disabled right now
    val bytecode = Hex.toHexString(serialize(contract))
    val fields = CompileResult.FieldsSig(
      contractAst.getFieldNames(),
      contractAst.getFieldTypes(),
      contractAst.getFieldMutability()
    )
    CompileContractResult(
      contractAst.name,
      bytecode,
      contract.hash,
      fields,
      functions = AVector.from(contractAst.funcs.view.map(CompileResult.FunctionSig.from)),
      events = AVector.from(contractAst.events.map(CompileResult.EventSig.from)),
      warnings = warnings
    )
  }
}

final case class CompileProjectResult(
    contracts: AVector[CompileContractResult],
    scripts: AVector[CompileScriptResult]
)

object CompileProjectResult {
  def from(
      contracts: AVector[(StatefulContract, Ast.Contract, AVector[String])],
      scripts: AVector[(StatefulScript, Ast.TxScript, AVector[String])]
  ): CompileProjectResult = {
    val compiledContracts = contracts.map(c => CompileContractResult.from(c._1, c._2, c._3))
    val compiledScripts   = scripts.map(s => CompileScriptResult.from(s._1, s._2, s._3))
    CompileProjectResult(compiledContracts, compiledScripts)
  }
}

object CompileResult {

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
        func.useAssetsInContract,
        func.isPublic,
        func.getArgNames(),
        func.getArgTypeSignatures(),
        func.getArgMutability(),
        func.getReturnSignatures()
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
}
