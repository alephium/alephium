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

import org.alephium.protocol.vm.Val
import org.alephium.util.{AlephiumSpec, AVector, Hex}

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
         |  @using(checkExternalCaller = false)
         |  pub fn f() -> () {
         |    proxy()
         |  }
         |}
         |
         |$internalCalls
         |""".stripMargin
  }

  it should "check permission for external calls" in new ExternalCallsFixture {
    val warnings0 = Compiler.compileContractFull(externalCalls, 0).rightValue.warnings
    checkExternalCallerWarnings(warnings0).isEmpty is true

    val warnings1 = Compiler.compileContractFull(internalCalls, 0).rightValue.warnings
    checkExternalCallerWarnings(warnings1).isEmpty is true
  }

  it should "check permission for recursive calls" in {
    {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn a() -> () {
           |    a()
           |  }
           |}
           |""".stripMargin

      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      checkExternalCallerWarnings(warnings).isEmpty is true
    }

    {
      val code =
        s"""
           |Contract Foo() {
           |  pub fn a() -> () {
           |    b()
           |  }
           |
           |  pub fn b() -> () {
           |    a()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      checkExternalCallerWarnings(warnings).isEmpty is true
    }

    {
      val code =
        s"""
           |Contract Foo(mut v: U256) {
           |  pub fn x() -> () {
           |    b()
           |  }
           |
           |  @using(updateFields = true)
           |  pub fn a() -> () {
           |    b()
           |    v = 0
           |  }
           |
           |  pub fn b() -> () {
           |    a()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      checkExternalCallerWarnings(warnings).toSet is Set(
        Warnings.noCheckExternalCallerMsg("Foo", "x"),
        Warnings.noCheckExternalCallerMsg("Foo", "a"),
        Warnings.noCheckExternalCallerMsg("Foo", "b")
      )
    }

    {
      val code =
        s"""
           |Contract Foo(bar: Bar) {
           |  pub fn a() -> () {
           |    bar.a()
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn a() -> () {
           |    foo.a()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileProject(code).rightValue._1.flatMap(_.warnings)
      checkExternalCallerWarnings(warnings).isEmpty is true
    }

    {
      val code =
        s"""
           |Contract Foo(bar: Bar, mut v: U256) {
           |  @using(updateFields = true)
           |  pub fn a() -> () {
           |    bar.a()
           |    v = 0
           |  }
           |}
           |
           |Contract Bar(foo: Foo) {
           |  pub fn a() -> () {
           |    foo.a()
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileProject(code).rightValue._1.flatMap(_.warnings)
      checkExternalCallerWarnings(warnings).toSet is Set(
        Warnings.noCheckExternalCallerMsg("Foo", "a"),
        Warnings.noCheckExternalCallerMsg("Bar", "a")
      )
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
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings.isEmpty is true
    }

    {
      info("No warnings if internal calls are pure")
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
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings.isEmpty is true
    }

    {
      info("Warning if internal calls changes state")
      val code =
        s"""
           |Contract Foo(mut state: U256) {
           |  pub fn getState() -> U256 {
           |    update()
           |    return state
           |  }
           |
           |  @using(updateFields = true)
           |  fn update() -> () {
           |    state = 0
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }

    {
      info("No warnings if external calls are pure")
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
           |  pub fn doNothing() -> () {
           |    return
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings.isEmpty is true
    }

    {
      info("Warning if external calls changes state")
      val code =
        s"""
           |Contract Foo(state: U256, bar: Bar) {
           |  pub fn getState() -> U256 {
           |    bar.update()
           |    return state
           |  }
           |}
           |
           |Contract Bar(foo: Foo, mut v: U256) {
           |  @using(updateFields = true)
           |  pub fn update() -> () {
           |    v = 0
           |  }
           |}
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
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
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
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
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
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
           |""".stripMargin
      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "getState"))
    }

    {
      info("No warning if the function call simple builtin functions")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    panic!()
           |  }
           |}
           |""".stripMargin

      Compiler.compileContractFull(code, 0).rightValue.warnings.isEmpty is true
    }

    {
      info("Warning if the function call builtin functions that need to check external caller")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo() -> () {
           |    migrate!(#)
           |  }
           |}
           |""".stripMargin

      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "foo"))
    }

    {
      info("Warning if the function has sub interface function call")
      val code =
        s"""
           |Contract Foo() {
           |  pub fn foo(barId: ByteVec) -> () {
           |    Bar(barId).bar()
           |  }
           |}
           |
           |Interface Bar {
           |  pub fn bar() -> ()
           |}
           |""".stripMargin

      val warnings = Compiler.compileContractFull(code, 0).rightValue.warnings
      warnings is AVector(Warnings.noCheckExternalCallerMsg("Foo", "foo"))
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
         |
         |  @using(checkExternalCaller = false)
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
         |  @using(checkExternalCaller = false)
         |  pub fn bar() -> () { foo1() }
         |}
         |Contract Baz() extends Foo() {
         |  @using(checkExternalCaller = false)
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

  it should "check interface std id" in {
    val foo = Ast.ContractInterface(None, Ast.TypeId("Foo"), Seq.empty, Seq.empty, Seq.empty)
    val bar = foo.copy(ident = Ast.TypeId("Bar"))
    val baz = foo.copy(ident = Ast.TypeId("Baz"))

    Ast.MultiContract.getStdId(Seq.empty) is None
    Ast.MultiContract.getStdId(Seq(foo)) is None
    Ast.MultiContract.getStdId(
      Seq(foo.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))))
    ) is Some(Val.ByteVec(Hex.unsafe("0001")))
    Ast.MultiContract.getStdId(Seq(foo, bar, baz)) is None
    Ast.MultiContract.getStdId(
      Seq(foo.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))), bar, baz)
    ) is Some(Val.ByteVec(Hex.unsafe("0001")))
    Ast.MultiContract.getStdId(
      Seq(foo, bar.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))), baz)
    ) is Some(Val.ByteVec(Hex.unsafe("0001")))
    Ast.MultiContract.getStdId(
      Seq(
        foo.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))),
        bar,
        baz.copy(stdId = Some(Val.ByteVec(Hex.unsafe("000101"))))
      )
    ) is Some(Val.ByteVec(Hex.unsafe("000101")))
    Ast.MultiContract.getStdId(
      Seq(
        foo.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))),
        bar.copy(stdId = Some(Val.ByteVec(Hex.unsafe("000101")))),
        baz.copy(stdId = Some(Val.ByteVec(Hex.unsafe("00010101"))))
      )
    ) is Some(Val.ByteVec(Hex.unsafe("00010101")))
    intercept[Compiler.Error](
      Ast.MultiContract.getStdId(
        Seq(
          foo.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))),
          bar,
          baz.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001"))))
        )
      )
    ).message is "The std id of interface Baz is the same as parent interface"
    intercept[Compiler.Error](
      Ast.MultiContract.getStdId(
        Seq(
          foo.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0001")))),
          bar,
          baz.copy(stdId = Some(Val.ByteVec(Hex.unsafe("0002"))))
        )
      )
    ).message is "The std id of interface Baz should starts with 0001"
  }

  it should "check if the contract std id enabled" in {
    val foo = Ast.Contract(
      None,
      None,
      false,
      Ast.TypeId("Foo"),
      Seq.empty,
      Seq.empty,
      Seq.empty,
      Seq.empty,
      Seq.empty,
      Seq.empty,
      Seq.empty
    )

    Ast.MultiContract.getStdIdEnabled(Seq.empty, foo.ident) is true
    Ast.MultiContract.getStdIdEnabled(Seq(foo), foo.ident) is true
    Ast.MultiContract.getStdIdEnabled(Seq(foo.copy(stdIdEnabled = Some(true))), foo.ident) is true
    Ast.MultiContract.getStdIdEnabled(Seq(foo.copy(stdIdEnabled = Some(false))), foo.ident) is false
    Ast.MultiContract.getStdIdEnabled(
      Seq(foo, foo.copy(stdIdEnabled = Some(true))),
      foo.ident
    ) is true
    Ast.MultiContract.getStdIdEnabled(
      Seq(foo, foo.copy(stdIdEnabled = Some(false))),
      foo.ident
    ) is false
    Ast.MultiContract.getStdIdEnabled(
      Seq(foo, foo.copy(stdIdEnabled = Some(true)), foo.copy(stdIdEnabled = Some(true))),
      foo.ident
    ) is true
    Ast.MultiContract.getStdIdEnabled(
      Seq(foo, foo.copy(stdIdEnabled = Some(false)), foo.copy(stdIdEnabled = Some(false))),
      foo.ident
    ) is false
    intercept[Compiler.Error](
      Ast.MultiContract.getStdIdEnabled(
        Seq(foo, foo.copy(stdIdEnabled = Some(false)), foo.copy(stdIdEnabled = Some(true))),
        foo.ident
      )
    ).message is "There are different std id enabled options on the inheritance chain of contract Foo"
  }
}
