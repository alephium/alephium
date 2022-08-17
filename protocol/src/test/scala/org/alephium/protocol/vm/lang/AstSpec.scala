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

import org.alephium.protocol.vm.lang.Ast.MultiContract
import org.alephium.util.AlephiumSpec

class AstSpec extends AlephiumSpec {

  behavior of "Permission check"

  it should "detect direct permission check" in {
    val code =
      s"""
         |Contract Foo() {
         |  fn foo() -> () {
         |    assert!(true, 0)
         |  }
         |
         |  fn bar() -> () {
         |    checkPermission!(false, 1)
         |  }
         |
         |  @using(permissionCheck = false)
         |  fn baz() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val (_, contractAst, _) = Compiler.compileContractFull(code).rightValue
    val foo                 = contractAst.funcs(0)
    val bar                 = contractAst.funcs(1)
    val baz                 = contractAst.funcs(2)
    foo.id.name is "foo"
    foo.hasDirectPermissionCheck() is false
    bar.id.name is "bar"
    bar.hasDirectPermissionCheck() is true
    baz.id.name is "baz"
    baz.hasDirectPermissionCheck() is true
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
         |    checkPermission!(true, 0)
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
         |  fn c() -> () {
         |    a()
         |  }
         |
         |  fn d() -> () {
         |    b()
         |  }
         |
         |  pub fn e() -> () {
         |    checkPermission!(true, 0)
         |  }
         |
         |  // permission check in another public function does not help
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
         |  @using(permissionCheck = false)
         |  pub fn i() -> () {
         |    noCheck()
         |  }
         |}
         |""".stripMargin
  }

  it should "build permission check table" in new InternalCallFixture {
    val contracts = fastparse.parse(internalCalls, StatefulParser.multiContract(_)).get.value
    val state     = Compiler.State.buildFor(contracts, 0)
    val contract  = contracts.contracts(0).asInstanceOf[Ast.Contract]
    contract.genCode(state)
    val interallCalls = state.internalCalls.map { case (caller, callees) =>
      caller.name -> callees.map(_.name).toSeq.sorted
    }
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

    val table = contract.buildPermissionCheckTable(state)
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
         |    checkPermission!(true, 0)
         |  }
         |
         |  // no need to check permission since it does not call external functions
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
         |}
         |
         |$internalCalls
         |""".stripMargin
  }

  it should "check permission for external calls" in new ExternalCallsFixture {
    val (_, _, warnings) = Compiler.compileContractFull(externalCalls, 0).rightValue
    warnings.toSet is Set(
      MultiContract.noPermissionCheckWarning("ExternalCalls", "a"),
      MultiContract.noPermissionCheckWarning("ExternalCalls", "c")
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
         |    checkPermission!(true, 0)
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
    val (_, _, warnings) = Compiler.compileContractFull(code, 0).rightValue
    warnings.toSet is Set(MultiContract.noPermissionCheckWarning("Foo", "a"))
  }

  it should "display the right warning message for permission check" in {
    MultiContract.noPermissionCheckWarning("Foo", "bar") is
      "No permission check for function: Foo.bar, please use checkPermission!(...) for the function or its private callees."
  }
}
