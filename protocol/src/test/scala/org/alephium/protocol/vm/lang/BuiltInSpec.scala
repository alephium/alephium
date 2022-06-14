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

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.{Instr, SelfAddress, StatefulContext}
import org.alephium.protocol.vm.lang.BuiltIn.SimpleStatefulBuiltIn
import org.alephium.util.AlephiumSpec

class BuiltInSpec extends AlephiumSpec {
  it should "check all functions that can use preapproved assets" in {
    BuiltIn.statelessFuncs.values.count(_.usePreapprovedAssets) is 0
    BuiltIn.statefulFuncs.values.filter(_.usePreapprovedAssets).toSet is
      Set[Compiler.FuncInfo[StatefulContext]](
        BuiltIn.lockApprovedAssets,
        BuiltIn.createContract,
        BuiltIn.createContractWithToken,
        BuiltIn.copyCreateContract,
        BuiltIn.copyCreateContractWithToken,
        BuiltIn.createSubContract,
        BuiltIn.createSubContractWithToken,
        BuiltIn.copyCreateSubContract,
        BuiltIn.copyCreateSubContractWithToken
      )
  }

  it should "check all functions that can use assets in contract" in {
    BuiltIn.statelessFuncs.values.count(_.useAssetsInContract) is 0
    BuiltIn.statefulFuncs.values
      .filter(_.useAssetsInContract)
      .toSet
      .map((f: Compiler.FuncInfo[StatefulContext]) =>
        f.asInstanceOf[SimpleStatefulBuiltIn].instr.asInstanceOf[Instr[_]]
      ) is Ast.ContractAssets.contractAssetsInstrs.-(SelfAddress)
  }
}
