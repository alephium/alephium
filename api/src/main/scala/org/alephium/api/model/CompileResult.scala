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
    bytecodeTemplate: String,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig]
)

object CompileScriptResult {
  def from(script: StatefulScript, scriptAst: Ast.TxScript): CompileScriptResult = {
    val bytecodeTemplate = script.toTemplateString()
    val fields = CompileResult.FieldsSig(
      scriptAst.getTemplateVarsSignature(),
      AVector.from(scriptAst.getTemplateVarsNames()),
      AVector.from(scriptAst.getTemplateVarsTypes())
    )
    CompileScriptResult(
      bytecodeTemplate,
      fields = fields,
      functions = AVector.from(scriptAst.funcs.view.map(CompileResult.FunctionSig.from))
    )
  }
}

final case class CompileContractResult(
    bytecode: String,
    codeHash: Hash,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    events: AVector[CompileResult.EventSig]
)

object CompileContractResult {
  def from(contract: StatefulContract, contractAst: Ast.TxContract): CompileContractResult = {
    assume(contractAst.templateVars.isEmpty) // Template variable is disabled right now
    val bytecode = Hex.toHexString(serialize(contract))
    val fields = CompileResult.FieldsSig(
      contractAst.getFieldsSignature(),
      AVector.from(contractAst.getFieldNames()),
      AVector.from(contractAst.getFieldTypes())
    )
    CompileContractResult(
      bytecode,
      contract.hash,
      fields,
      functions = AVector.from(contractAst.funcs.view.map(CompileResult.FunctionSig.from)),
      events = AVector.from(contractAst.events.map(CompileResult.EventSig.from))
    )
  }
}

object CompileResult {

  final case class FieldsSig(signature: String, names: AVector[String], types: AVector[String])

  final case class FunctionSig(
      name: String,
      signature: String,
      argNames: AVector[String],
      argTypes: AVector[String],
      returnTypes: AVector[String]
  )
  object FunctionSig {
    def from(func: Ast.FuncDef[StatefulContext]): FunctionSig = {
      FunctionSig(
        func.id.name,
        func.signature,
        AVector.from(func.getArgNames()),
        AVector.from(func.getArgTypeSignatures()),
        AVector.from(func.getReturnSignatures())
      )
    }
  }

  final case class EventSig(
      name: String,
      signature: String,
      fieldNames: AVector[String],
      fieldTypes: AVector[String]
  )
  object EventSig {
    def from(event: Ast.EventDef): EventSig = {
      EventSig(
        event.name,
        event.signature,
        AVector.from(event.getFieldNames()),
        AVector.from(event.getFieldTypeSignatures())
      )
    }
  }
}
