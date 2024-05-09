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

import org.alephium.protocol.vm._
import org.alephium.ralph.BuiltIn.{OverloadedSimpleBuiltIn, SimpleBuiltIn}
import org.alephium.util.AlephiumSpec

class BuiltInSpec extends AlephiumSpec {
  it should "check all functions that can use preapproved assets" in {
    BuiltIn.statelessFuncs.values.count(_.usePreapprovedAssets) is 0
    BuiltIn.statefulFuncs.values.filter(_.usePreapprovedAssets).toSet is
      Set[BuiltIn.BuiltIn[StatefulContext]](
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
    BuiltIn.statelessFuncs.values.count(_.useAssetsInContract != Ast.NotUseContractAssets) is 0
    BuiltIn.statefulFuncs.values
      .filter(_.useAssetsInContract != Ast.NotUseContractAssets)
      .flatMap {
        case f: SimpleBuiltIn[_] => f.instrs
        case f: OverloadedSimpleBuiltIn[_] =>
          f.argsTypeWithInstrs(0)
            .instrs
            .asInstanceOf[Seq[Instr[_]]]
        case _: Any => Seq.empty[Instr[_]]
      }
      .toSet is StaticAnalysis.contractAssetsInstrs.--(
      Set(SelfAddress, TransferAlphFromSelf, TransferAlphToSelf)
    )
  }

  it should "initialize built-in encoding functions for contracts" in {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val ast         = Compiler.compileContractFull(code).rightValue.ast
    val globalState = Ast.GlobalState(Seq.empty)
    ast.builtInContractFuncs(globalState).length is 1
    val funcTable = ast.funcTable(globalState)
    funcTable(Ast.FuncId("encodeFields", true)).genCode(Seq.empty) is Seq.empty
  }

  it should "initialize built-in encoding functions for contracts using standard interfaces" in {
    def code(enabled: Boolean): String =
      s"""
         |@std(enabled = $enabled)
         |Contract Foo() implements IFoo {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |@std(id = #ffff)
         |Interface IFoo {
         |  pub fn foo() -> ()
         |}
         |""".stripMargin

    def test(enabled: Boolean) = {
      val ast         = Compiler.compileContractFull(code(enabled)).rightValue.ast
      val globalState = Ast.GlobalState(Seq.empty)
      val funcTable   = ast.funcTable(globalState)
      funcTable.size is 2
      ast.builtInContractFuncs(globalState).length is 1

      val foo = funcTable(Ast.FuncId("foo", false))
      foo.isStatic is false
      val encodeFields = funcTable(Ast.FuncId("encodeFields", true))
      encodeFields.isStatic is true
      encodeFields.genCode(Seq.empty) is Seq.empty
    }

    test(true)
    test(false)
  }

  it should "return correct error source index" in {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    assert!($$addModN!(2, 4) == 1, 0)
         |    return
         |  }
         |}
         |""".stripMargin
    val index = code.indexOf("$")
    val error = Compiler.compileContractFull(code.replace("$", "")).leftValue

    error.message is "Invalid args type \"List(U256, U256)\" for builtin func addModN, expected \"List(U256, U256, U256)\""
    error.position is index
  }

  it should "display contract type correctly in error message" in {
    val barCode = "Contract BarContract() {}"
    val invalidArgsCode =
      s"""
         |Contract Foo(barContract: BarContract) {
         |  pub fn foo() -> () {
         |    let _ = $$subContractId!(barContract)
         |  }
         |}
         |$barCode
         |""".stripMargin
    val invalidArgsIndex = invalidArgsCode.indexOf("$")
    val invalidArgsError = Compiler.compileContractFull(invalidArgsCode.replace("$", "")).leftValue

    invalidArgsError.message is "Invalid args type \"List(BarContract)\" for builtin func subContractId, expected \"List(ByteVec)\""
    invalidArgsError.position is invalidArgsIndex

    val invalidReturnCode =
      s"""
         |Contract Foo(barContract: BarContract) {
         |  pub fn foo() -> ByteVec {
         |    $$return barContract
         |  }
         |}
         |$barCode
         |""".stripMargin
    val invalidReturnIndex = invalidReturnCode.indexOf("$")
    val invalidReturnError =
      Compiler.compileContractFull(invalidReturnCode.replace("$", "")).leftValue

    invalidReturnError.message is s"Invalid return types \"List(BarContract)\" for func foo, expected \"List(ByteVec)\""
    invalidReturnError.position is invalidReturnIndex
  }
}
