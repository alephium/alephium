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

import org.alephium.util.{AlephiumSpec, AVector}

class AstSpec extends AlephiumSpec {

  behavior of "Check external caller"

  def checkExternalCallerWarnings(warnings: AVector[String]): AVector[String] = {
    warnings.filter(_.startsWith("No check external caller"))
  }

  it should "detect direct check external caller" in {
    val code =
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    assert!(true, 0)
         |  }
         |
         |  fn bar() -> () {
         |    checkCaller!(false, 1)
         |  }
         |
         |  @using(checkExternalCaller = false)
         |  fn baz() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contractAst = Compiler.compileContractFull(code).rightValue.ast
    val foo         = contractAst.funcs(0)
    val bar         = contractAst.funcs(1)
    val baz         = contractAst.funcs(2)
    foo.id.name is "foo"
    foo.hasDirectCheckExternalCaller() is false
    bar.id.name is "bar"
    bar.hasDirectCheckExternalCaller() is true
    baz.id.name is "baz"
    baz.hasDirectCheckExternalCaller() is true
  }

  trait InternalCallFixture {
    val internalCalls =
      s"""
         |Contract InternalCalls() {
         |  fn noCheck() -> () {
         |    return
         |  }
         |
         |  fn check() -> () {
         |    checkCaller!(true, 0)
         |  }
         |
         |  fn a() -> () {
         |    noCheck()
         |  }
         |
         |  fn b() -> () {
         |    check()
         |  }
         |
         |  pub fn c() -> () {
         |    a()
         |  }
         |
         |  pub fn d() -> () {
         |    b()
         |  }
         |
         |  pub fn e() -> () {
         |    checkCaller!(true, 0)
         |  }
         |
         |  // check external caller in another public function does not help
         |  pub fn f() -> () {
         |    e()
         |  }
         |
         |  pub fn g() -> () {
         |    a()
         |    c()
         |    e()
         |  }
         |
         |  pub fn h() -> () {
         |    a()
         |    c()
         |    e()
         |    b()
         |  }
         |
         |  @using(checkExternalCaller = false)
         |  pub fn i() -> () {
         |    noCheck()
         |  }
         |}
         |""".stripMargin
  }

  it should "build check external caller table" in new InternalCallFixture {
    val contracts = fastparse.parse(internalCalls, StatefulParser.multiContract(_)).get.value
    val state     = Compiler.State.buildFor(contracts, 0)(CompilerOptions.Default)
    val contract  = contracts.contracts(0).asInstanceOf[Ast.Contract]
    contract.check(state)
    contract.genCode(state)
    val interallCalls = state.internalCalls
      .map { case (caller, callees) =>
        caller.name -> callees.view.filterNot(_.isBuiltIn).map(_.name).toSeq.sorted
      }
      .filter(_._2.nonEmpty)
    interallCalls is mutable.HashMap(
      "a" -> Seq("noCheck"),
      "b" -> Seq("check"),
      "c" -> Seq("a"),
      "d" -> Seq("b"),
      "f" -> Seq("e"),
      "g" -> Seq("a", "c", "e"),
      "h" -> Seq("a", "b", "c", "e"),
      "i" -> Seq("noCheck")
    )
    state.internalCalls.foreach { case (funcId, _) =>
      state.hasSubFunctionCall(funcId) is true
    }
    state.hasSubFunctionCall(Ast.FuncId("noCheck", false)) is false
    state.externalCalls.isEmpty is true

    val table = contract.buildCheckExternalCallerTable(state)
    table.map { case (fundId, checked) => fundId.name -> checked } is
      mutable.Map(
        "noCheck" -> false,
        "check"   -> true,
        "a"       -> false,
        "b"       -> true,
        "c"       -> false,
        "d"       -> true,
        "e"       -> true,
        "f"       -> false,
        "g"       -> false,
        "h"       -> true,
        "i"       -> true
      )
  }

  trait ExternalCallsFixture extends InternalCallFixture {
    val externalCalls =
      s"""
         |Contract ExternalCalls(callee: InternalCalls) {
         |  fn noCheckPri() -> () {
         |    return
         |  }
         |
         |  fn checkPri() -> () {
         |    checkCaller!(true, 0)
         |  }
         |
         |  // no need to check external call since it does not call external functions
         |  pub fn noCheck() -> () {
         |    noCheckPri()
         |  }
         |
         |  pub fn check() -> () {
         |    checkPri()
         |  }
         |
         |  pub fn a() -> () {
         |    callee.g()
         |  }
         |
         |  pub fn b() -> () {
         |    callee.h()
         |  }
         |
         |  pub fn c() -> () {
         |    callee.g()
         |    callee.f()
         |  }
         |
         |  pub fn d() -> () {
         |    callee.g()
         |    callee.f()
         |    callee.h()
         |  }
         |
         |  pub fn e() -> () {
         |    callee.g()
         |    callee.f()
         |    callee.i()
         |  }
         |
         |  fn proxy() -> () {
         |    callee.c()
         |  }
         |
         |  pub fn f() -> () {
         |    proxy()
         |  }
         |}
         |
         |$internalCalls
         |""".stripMargin
  }

  it should "check permission for external calls" in new ExternalCallsFixture {
    val contracts = fastparse.parse(externalCalls, StatefulParser.multiContract(_)).get.value
    val state     = Compiler.State.buildFor(contracts, 0)(CompilerOptions.Default)
    state.internalCalls.foreach { case (funcId, _) =>
      state.hasSubFunctionCall(funcId) is true
    }
    state.externalCalls.foreach { case (funcId, _) =>
      state.hasSubFunctionCall(funcId) is true
    }
    state.hasSubFunctionCall(Ast.FuncId("noCheckPri", false)) is false

    val warnings = Compiler.compileContractFull(externalCalls, 0).rightValue.warnings
    checkExternalCallerWarnings(warnings).toSet is Set(
      Warnings.noCheckExternalCallerMsg("InternalCalls", "c"),
      Warnings.noCheckExternalCallerMsg("InternalCalls", "f"),
      Warnings.noCheckExternalCallerMsg("InternalCalls", "g")
    )
  }

  trait MutualRecursionFixture {
    val code =
      s"""
         |Contract Foo(bar: Bar) {
         |  pub fn a() -> () {
         |    bar.a()
         |  }
         |  pub fn b() -> () {
         |    checkCaller!(true, 0)
         |  }
         |}
         |
         |Contract Bar(foo: Foo) {
         |  pub fn a() -> () {
         |    foo.b()
         |  }
         |}
         |""".stripMargin
  }

  it should "not check permission for mutual recursive calls" in new MutualRecursionFixture {
    val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
    checkExternalCallerWarnings(warnings).toSet is Set(
      Warnings.noCheckExternalCallerMsg("Bar", "a")
    )
  }

  it should "test check external caller for interface" in {
    {
      info("No error for simple view functions")
      val code =
        s"""
           |Interface Foo {
           |  pub fn foo() -> U256
           |}
           |Contract Bar() implements Foo {
           |  pub fn foo() -> U256 {
           |    return 0
           |  }
           |}
           |""".stripMargin
      Compiler.compileProject(code).isRight is true
    }

    {
      info("No error if impl contract checks external caller")
      val code =
        s"""
           |Interface Foo {
           |  @using(updateFields = true)
           |  pub fn foo() -> ()
           |  pub fn bar() -> ()
           |  @using(preapprovedAssets = true)
           |  pub fn baz() -> ()
           |  @using(assetsInContract = true)
           |  pub fn qux(address: Address) -> ()
           |}
           |Contract Bar(mut a: U256) implements Foo {
           |  @using(updateFields = true)
           |  pub fn foo() -> () {
           |    checkCaller!(true, 0)
           |    a = 0
           |  }
           |
           |  fn bar_() -> () {}
           |  pub fn bar() -> () {
           |    checkCaller!(true, 0)
           |    bar_()
           |  }
           |
           |  @using(preapprovedAssets = true)
           |  pub fn baz() -> () {
           |    checkCaller!(true, 0)
           |  }
           |
           |  @using(assetsInContract = true)
           |  pub fn qux(address: Address) -> () {
           |    checkCaller!(true, 0)
           |    transferTokenFromSelf!(address, selfTokenId!(), 1)
           |  }
           |}
           |""".stripMargin
      Compiler.compileProject(code).isRight is true
    }

    {
      info("Error if impl contract does not check external caller")
      def test(code: String, typeId: String, funcId: String) = {
        val error = Compiler.compileProject(code).leftValue
        error.message is Warnings.noCheckExternalCallerMsg(typeId, funcId)
      }

      test(
        s"""
           |Interface Foo {
           |  @using(updateFields = true)
           |  pub fn foo() -> ()
           |}
           |Contract Bar(mut a: U256) implements Foo {
           |  @using(updateFields = true)
           |  pub fn foo() -> () {
           |    a = 0
           |  }
           |}
           |""".stripMargin,
        "Bar",
        "foo"
      )

      test(
        s"""
           |Interface Foo {
           |  @using(preapprovedAssets = true)
           |  pub fn foo() -> ()
           |}
           |Contract Bar() implements Foo {
           |  @using(preapprovedAssets = true)
           |  pub fn foo() -> () {}
           |}
           |""".stripMargin,
        "Bar",
        "foo"
      )

      test(
        s"""
           |Interface Foo {
           |  @using(assetsInContract = true)
           |  pub fn foo(address: Address) -> ()
           |}
           |Contract Bar() implements Foo {
           |  @using(assetsInContract = true)
           |  pub fn foo(address: Address) -> () {
           |    transferTokenFromSelf!(address, selfTokenId!(), 1)
           |  }
           |}
           |""".stripMargin,
        "Bar",
        "foo"
      )

      test(
        s"""
           |Interface Foo {
           |  pub fn foo() -> ()
           |}
           |Contract Bar() implements Foo {
           |  fn foo_() -> () {}
           |  pub fn foo() -> () {
           |    foo_()
           |  }
           |}
           |""".stripMargin,
        "Bar",
        "foo"
      )
    }

    {
      info("not check external caller for interface function calls")
      def code(checkExternalCaller: Boolean) =
        s"""
           |Contract Bar() {
           |  pub fn bar(fooId: ByteVec) -> () {
           |    Foo(fooId).foo()
           |  }
           |}
           |Interface Foo {
           |  @using(checkExternalCaller = $checkExternalCaller, updateFields = true)
           |  pub fn foo() -> ()
           |}
           |""".stripMargin

      val warnings0 = Compiler.compileContractFull(code(true), 0).rightValue.warnings
      warnings0.isEmpty is true
      val warnings1 = Compiler.compileContractFull(code(false), 0).rightValue.warnings
      warnings1.isEmpty is true
    }

    {
      info("implemented function have check external caller in private callee")
      val code =
        s"""
           |Contract Bar() implements Foo {
           |  pub fn foo() -> () {
           |    bar()
           |  }
           |  fn bar() -> () {
           |    checkCaller!(true, 0)
           |  }
           |}
           |Interface Foo {
           |  pub fn foo() -> ()
           |}
           |""".stripMargin

      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings.isEmpty is true
    }
  }

  it should "skip check external caller for simple view functions" in {
    {
      info("No warnings for simple view function")
      val code =
        s"""
           |Contract Foo(state: U256) {
           |  pub fn getState() -> U256 {
           |    return state
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn getState() -> U256 {
           |    return foo.getState()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 1).rightValue.warnings
      warnings.isEmpty is true
    }

    {
      info("Warning if the function has internal calls")
      val code =
        s"""
           |Contract Foo(state: U256) {
           |  pub fn getState() -> U256 {
           |    doNothing()
           |    return state
           |  }
           |
           |  fn doNothing() -> () {
           |    return
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn getState() -> U256 {
           |    return foo.getState()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 1).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }

    {
      info("Warning if the function has external calls")
      val code =
        s"""
           |Contract Foo(state: U256, bar: Bar) {
           |  pub fn getState() -> U256 {
           |    bar.doNothing()
           |    return state
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn getState() -> U256 {
           |    return foo.getState()
           |  }
           |
           |  pub fn doNothing() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 1).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }

    {
      info("Warning if the function changes contract fields")
      val code =
        s"""
           |Contract Foo(mut state: U256) {
           |  @using(updateFields = true)
           |  pub fn getState() -> U256 {
           |    state = 0
           |    return state
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn getState() -> U256 {
           |    return foo.getState()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 1).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }

    {
      info("Warning if the function use preapproved assets")
      val code =
        s"""
           |Contract Foo(state: U256) {
           |  @using(preapprovedAssets = true)
           |  pub fn getState() -> U256 {
           |    return state
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  @using(preapprovedAssets = true)
           |  pub fn getState() -> U256 {
           |    return foo.getState{callerAddress!() -> ALPH: 1 alph}()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 1).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }

    {
      info("Warning if the function use contract assets")
      val code =
        s"""
           |Contract Foo(state: U256) {
           |  @using(assetsInContract = true)
           |  pub fn getState(address: Address) -> U256 {
           |    transferTokenFromSelf!(address, ALPH, 1 alph)
           |    return state
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn getState() -> U256 {
           |    return foo.getState(callerAddress!())
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 1).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }
  }

  it should "display the right warning message for check external caller" in {
    Warnings.noCheckExternalCallerMsg("Foo", "bar") is
      "No check external caller for function: Foo.bar, please use checkCaller!(...) for the function or its private callees."
  }

  behavior of "Private function usage"

  it should "check if private functions are used" in {
    val code0 =
      s"""
         |Contract Foo() {
         |  fn private0() -> () {}
         |  fn private1() -> () {}
         |  pub fn public() -> () {
         |    private0()
         |  }
         |}
         |""".stripMargin
    val warnings0 = Compiler.compileContractFull(code0, 0).rightValue.warnings
    warnings0 is AVector("Private function Foo.private1 is not used")

    val code1 =
      s"""
         |Abstract Contract Foo() {
         |  fn foo0() -> U256 {
         |    return 0
         |  }
         |  fn foo1() -> () {
         |    let _ = foo0()
         |  }
         |}
         |Contract Bar() extends Foo() {
         |  pub fn bar() -> () { foo1() }
         |}
         |Contract Baz() extends Foo() {
         |  pub fn baz() -> () { foo1() }
         |}
         |""".stripMargin
    val (contracts, _) = Compiler.compileProject(code1).rightValue
    contracts.length is 2
    contracts.foreach(_.warnings.isEmpty is true)
  }

  behavior of "Compiler"

  it should "check unique TxScript/Contract/Interface name" in {
    val code = s"""
                  |Abstract Contract Foo() {
                  |  fn foo() -> () {}
                  |}
                  |
                  |Contract Bar() extends Foo() {
                  |  fn foo() -> () {}
                  |}
                  |
                  |Contract Bar() extends Foo() {
                  |  fn bar() -> () {}
                  |}
                  |
                  |Interface Foo {
                  |  fn foo() -> ()
                  |}
                  |
                  |Interface Main {
                  |  fn foo() -> ()
                  |}
                  |
                  |TxScript Main {
                  |  return
                  |}
                  |""".stripMargin
    val error = Compiler.compileProject(code).leftValue
    error.message is "These TxScript/Contract/Interface are defined multiple times: Bar, Foo, Main"
  }
}
