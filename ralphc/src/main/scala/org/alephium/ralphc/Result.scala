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

package org.alephium.ralphc

import org.alephium.api.model.{CompileContractResult, CompileResult, CompileScriptResult}
import org.alephium.protocol.Hash
import org.alephium.util.AVector

final case class ScriptResult(
    version: String,
    name: String,
    bytecodeTemplate: String,
    fieldsSig: CompileResult.FieldsSig,
    functions: AVector[CompileResult.FunctionSig]
) extends CompileResult.Versioned

object ScriptResult {
  def from(s: CompileScriptResult): ScriptResult = {
    ScriptResult(
      s.version,
      s.name,
      s.bytecodeTemplate,
      s.fields,
      s.functions
    )
  }
}

final case class ContractResult(
    version: String,
    name: String,
    bytecode: String,
    codeHash: Hash,
    fieldsSig: CompileResult.FieldsSig,
    eventsSig: AVector[CompileResult.EventSig],
    functions: AVector[CompileResult.FunctionSig]
) extends CompileResult.Versioned

object ContractResult {
  def from(c: CompileContractResult): ContractResult = {
    ContractResult(
      c.version,
      c.name,
      c.bytecode,
      c.codeHash,
      c.fields,
      c.events,
      c.functions
    )
  }
}
