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

import scala.collection.mutable

import org.alephium.util.AlephiumSpec
import org.alephium.util.AVector

class AstSpec extends AlephiumSpec {

  behavior of "External call check"

  def externalCallCheckWarnings(warnings: AVector[String]): AVector[String] = {
    warnings.filter(_.startsWith("No external call check"))
  }

  it should "detect direct external call check" in {
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
         |  @using(externalCallCheck = false)
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
    foo.hasDirectExternalCallCheck() is false
    bar.id.name is "bar"
    bar.hasDirectExternalCallCheck() is true
    baz.id.name is "baz"
    baz.hasDirectExternalCallCheck() is true
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
         |  // external call check in another public function does not help
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
         |  @using(externalCallCheck = false)
         |  pub fn i() -> () {
         |    noCheck()
         |  }
         |}
         |""".stripMargin
  }

  it should "build external call check table" in new InternalCallFixture {
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
    state.externalCalls.isEmpty is true

    val table = contract.buildExternalCallCheckTable(state)
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
    val warnings = Compiler.compileContractFull(externalCalls, 0).rightValue.warnings
    externalCallCheckWarnings(warnings).toSet is Set(
      Warnings.noExternalCallCheckMsg("InternalCalls", "c"),
      Warnings.noExternalCallCheckMsg("InternalCalls", "f"),
      Warnings.noExternalCallCheckMsg("InternalCalls", "g")
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
    externalCallCheckWarnings(warnings).toSet is Set(
      Warnings.noExternalCallCheckMsg("Bar", "a")
    )
  }

  it should "test external call check for interface" in {
    {
      info("All contracts that inherit from the interface should have external call check")
      val code =
        s"""
           |Interface Base {
           |  pub fn base() -> ()
           |}
           |Contract Foo() implements Base {
           |  pub fn base() -> () {
           |    checkCaller!(true, 0)
           |  }
           |}
           |Abstract Contract A() implements Base {
           |  fn a() -> () {
           |    checkCaller!(true, 0)
           |  }
           |}
           |Contract Bar() extends A() {
           |  pub fn base() -> () {
           |    a()
           |  }
           |}
           |Contract Baz() implements Base {
           |  pub fn base() -> () {}
           |}
           |""".stripMargin
      val error = Compiler.compileProject(code).leftValue
      error.message is Warnings.noExternalCallCheckMsg("Baz", "base")
    }

    {
      info("not check external call check for interface function calls")
      def code(externalCallCheck: Boolean) =
        s"""
           |Contract Bar() {
           |  pub fn bar(fooId: ByteVec) -> () {
           |    Foo(fooId).foo()
           |  }
           |}
           |Interface Foo {
           |  @using(externalCallCheck = $externalCallCheck)
           |  pub fn foo() -> ()
           |}
           |""".stripMargin

      val warnings0 = Compiler.compileContractFull(code(true), 0).rightValue.warnings
      warnings0.isEmpty is true
      val warnings1 = Compiler.compileContractFull(code(false), 0).rightValue.warnings
      warnings1.isEmpty is true
    }

    {
      info("implemented function have external call check in private callee")
      val code =
        s"""
           |Contract Bar() implements Foo {
           |  @using(readonly = true)
           |  pub fn foo() -> () {
           |    bar()
           |  }
           |  @using(readonly = true)
           |  fn bar() -> () {
           |    checkCaller!(true, 0)
           |  }
           |}
           |Interface Foo {
           |  @using(readonly = true)
           |  pub fn foo() -> ()
           |}
           |""".stripMargin

      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings.isEmpty is true
    }
  }

  it should "display the right warning message for external call check" in {
    Warnings.noExternalCallCheckMsg("Foo", "bar") is
      "No external call check for function: Foo.bar, please use checkCaller!(...) for the function or its private callees."
  }

  behavior of "Private function usage"

  it should "check if private functions are used" in {
    val code0 =
      s"""
         |Contract Foo() {
         |  @using(readonly = true)
         |  fn private0() -> () {}
         |  @using(readonly = true)
         |  fn private1() -> () {}
         |  @using(readonly = true)
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
         |  @using(readonly = true)
         |  fn foo0() -> U256 {
         |    return 0
         |  }
         |  @using(readonly = true)
         |  fn foo1() -> () {
         |    let _ = foo0()
         |  }
         |}
         |Contract Bar() extends Foo() {
         |  @using(readonly = true)
         |  pub fn bar() -> () { foo1() }
         |}
         |Contract Baz() extends Foo() {
         |  @using(readonly = true)
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
