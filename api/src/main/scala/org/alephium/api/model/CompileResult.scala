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

import akka.util.ByteString

import org.alephium.protocol.Hash
import org.alephium.protocol.vm.{StatefulContext, StatefulContract, StatefulScript}
import org.alephium.protocol.vm.lang.Ast
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Hex}

final case class CompileScriptResult(
    compiled: CompiledScriptTrait,
    functions: AVector[CompileResult.FunctionSig],
    events: AVector[CompileResult.EventSig]
) {
  // this only works for script without template variables
  def bytecodeUnsafe: String = Hex.toHexString(compiled.asInstanceOf[SimpleScriptByteCode].bytecode)

  // this only works for script without template variables
  def codeHashUnsafe: Hash = {
    Hash.hash(compiled.asInstanceOf[SimpleScriptByteCode].bytecode)
  }
}

sealed trait CompiledScriptTrait
@upickle.implicits.key("TemplateScriptByteCode")
final case class TemplateScriptByteCode(
    templateByteCode: String
) extends CompiledScriptTrait
@upickle.implicits.key("SimpleScriptByteCode")
final case class SimpleScriptByteCode(
    bytecode: ByteString
) extends CompiledScriptTrait

object CompileScriptResult {
  def from(script: StatefulScript, scriptAst: Ast.TxScript): CompileScriptResult = {
    val compiled: CompiledScriptTrait = if (scriptAst.templateVars.isEmpty) {
      SimpleScriptByteCode(serialize(script))
    } else {
      TemplateScriptByteCode(script.toTemplateString())
    }
    CompileScriptResult(
      compiled = compiled,
      functions = AVector.from(scriptAst.funcs.view.map(CompileResult.FunctionSig.from)),
      events = AVector.from(scriptAst.events.map(CompileResult.EventSig.from))
    )
  }
}

final case class CompileContractResult(
    compiled: CompiledContractTrait,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    events: AVector[CompileResult.EventSig]
) {
  // this only works for contract without template variables
  def bytecodeUnsafe: String =
    Hex.toHexString(compiled.asInstanceOf[SimpleContractByteCode].bytecode)

  // this only works for contract without template variables
  def codeHashUnsafe: Hash = {
    Hash.hash(compiled.asInstanceOf[SimpleContractByteCode].bytecode)
  }
}

object CompileContractResult {
  def from(contract: StatefulContract, contractAst: Ast.TxContract): CompileContractResult = {
    val bytecode: CompiledContractTrait = if (contractAst.templateVars.isEmpty) {
      SimpleContractByteCode(serialize(contract))
    } else {
      TemplateContractByteCode(contract.fieldLength, contract.methods.map(_.toTemplateString()))
    }
    val fields = CompileResult.FieldsSig(
      contractAst.getFieldsSignature(),
      AVector.from(contractAst.getFieldTypes())
    )
    CompileContractResult(
      bytecode,
      fields,
      functions = AVector.from(contractAst.funcs.view.map(CompileResult.FunctionSig.from)),
      events = AVector.from(contractAst.events.map(CompileResult.EventSig.from))
    )
  }
}

sealed trait CompiledContractTrait
@upickle.implicits.key("TemplateContractByteCode")
final case class TemplateContractByteCode(
    filedLength: Int,
    methodsByteCode: AVector[String]
) extends CompiledContractTrait
@upickle.implicits.key("SimpleContractByteCode")
final case class SimpleContractByteCode(
    bytecode: ByteString
) extends CompiledContractTrait

object CompileResult {

  final case class FieldsSig(signature: String, types: AVector[String])

  final case class FunctionSig(
      name: String,
      signature: String,
      argTypes: AVector[String],
      returnTypes: AVector[String]
  )
  object FunctionSig {
    def from(func: Ast.FuncDef[StatefulContext]): FunctionSig = {
      FunctionSig(
        func.id.name,
        func.signature,
        AVector.from(func.getArgTypeSignatures()),
        AVector.from(func.getReturnSignatures())
      )
    }
  }

  final case class EventSig(name: String, signature: String, fieldTypes: AVector[String])
  object EventSig {
    def from(event: Ast.EventDef): EventSig = {
      EventSig(event.name, event.signature, AVector.from(event.getFieldTypeSignatures()))
    }
  }
}
