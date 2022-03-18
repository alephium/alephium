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

import org.alephium.protocol.{vm, Hash}
import org.alephium.protocol.vm.StatefulContext
import org.alephium.protocol.vm.lang.Ast
import org.alephium.serde.{serialize, Serde}
import org.alephium.util.AVector

final case class CompileResult(
    bytecode: ByteString,
    codeHash: Hash,
    fields: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig],
    events: AVector[CompileResult.EventSig]
)

object CompileResult {

  def from[T <: vm.Contract[_]: Serde](
      contract: T,
      contractAst: Ast.ContractWithState
  ): CompileResult = {
    val fields =
      FieldsSig(contractAst.getFieldsSignature(), AVector.from(contractAst.getFieldTypes()))
    CompileResult(
      bytecode = serialize(contract),
      codeHash = contract.hash,
      fields = fields,
      functions = AVector.from(contractAst.funcs.view.map(FunctionSig.from)),
      events = AVector.from(contractAst.events.map(EventSig.from))
    )
  }

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
