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

import org.alephium.protocol.vm.StatefulContext
import org.alephium.protocol.vm.lang.Ast
import org.alephium.serde.{serialize, Serde}
import org.alephium.util.AVector

final case class CompileResult(
    bytecode: ByteString,
    fieldSignature: String,
    functions: AVector[CompileResult.Function],
    events: AVector[CompileResult.Event]
)

object CompileResult {

  def from[T: Serde](contract: T, contractAst: Ast.ContractWithState): CompileResult = {
    CompileResult(
      bytecode = serialize(contract),
      fieldSignature = contractAst.getFieldSignature(),
      functions = AVector.from(contractAst.funcs.view.map(Function.from)),
      events = AVector.from(contractAst.events.map(Event.from))
    )
  }

  final case class Function(id: String, signature: String)
  object Function {
    def from(func: Ast.FuncDef[StatefulContext]): Function = {
      Function(func.id.name, func.signature)
    }
  }

  final case class Event(id: String, signature: String)
  object Event {
    def from(event: Ast.EventDef): Event = {
      Event(event.name, event.signature)
    }
  }
}
