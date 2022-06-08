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

import org.alephium.util.AlephiumSpec

class TypeSpec extends AlephiumSpec {
  it should "return correct signature" in new TypeSignatureFixture {
    contractAst.getFieldsSignature() is
      "TxContract Foo(aa:Bool,mut bb:U256,cc:I256,mut dd:ByteVec,ee:Address,ff:[[Bool;1];2])"
    contractAst.getFieldNames() is
      Seq("aa", "bb", "cc", "dd", "ee", "ff")
    contractAst.getFieldTypes() is
      Seq("Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]")
    contractAst.funcs.map(_.signature) is Seq(
      "@using(preapprovedAssets=true,assetsInContract=true) pub bar(a:Bool,mut b:U256,c:I256,mut d:ByteVec,e:Address,f:[[Bool;1];2])->(U256,I256,ByteVec,Address,[[Bool;1];2])"
    )
    contractAst.funcs.map(_.getArgNames()) is
      Seq(Seq("a", "b", "c", "d", "e", "f"))
    contractAst.funcs.map(_.getArgTypeSignatures()) is
      Seq(Seq("Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"))
    contractAst.funcs.map(_.getReturnSignatures()) is
      Seq(Seq("U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"))
    contractAst.events.map(_.signature) is Seq(
      "event Bar(a:Bool,b:U256,d:ByteVec,e:Address)"
    )
    contractAst.events.map(_.getFieldNames()) is
      Seq(Seq("a", "b", "d", "e"))
    contractAst.events.map(_.getFieldTypeSignatures()) is
      Seq(Seq("Bool", "U256", "ByteVec", "Address"))

    scriptAst.getTemplateVarsSignature() is
      "TxScript Foo(aa:Bool,bb:U256,cc:I256,dd:ByteVec,ee:Address)"
    scriptAst.getTemplateVarsNames() is
      Seq("aa", "bb", "cc", "dd", "ee")
    scriptAst.getTemplateVarsTypes() is
      Seq("Bool", "U256", "I256", "ByteVec", "Address")
    scriptAst.funcs.map(_.signature) is Seq(
      "@using(preapprovedAssets=true) pub main()->()",
      "pub bar(a:Bool,mut b:U256,c:I256,mut d:ByteVec,e:Address,f:[[Bool;1];2])->(U256,I256,ByteVec,Address,[[Bool;1];2])"
    )
    scriptAst.events.map(_.signature) is Seq.empty
  }
}

trait TypeSignatureFixture {
  val contractStr =
    s"""
       |TxContract Foo(aa: Bool, mut bb: U256, cc: I256, mut dd: ByteVec, ee: Address, ff: [[Bool;1];2]) {
       |  event Bar(a: Bool, b: U256, d: ByteVec, e: Address)
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn bar(a: Bool, mut b: U256, c: I256, mut d: ByteVec, e: Address, f: [[Bool;1];2]) -> (U256, I256, ByteVec, Address, [[Bool;1];2]) {
       |    emit Bar(aa, bb, dd, ee)
       |    return b, c, d, e, f
       |  }
       |}
       |""".stripMargin
  lazy val (contract, contractAst) = Compiler.compileContractFull(contractStr).toOption.get

  val scriptStr =
    s"""
       |TxScript Foo(aa: Bool, bb: U256, cc: I256, dd: ByteVec, ee: Address) {
       |  return
       |  pub fn bar(a: Bool, mut b: U256, c: I256, mut d: ByteVec, e: Address, f: [[Bool;1];2]) -> (U256, I256, ByteVec, Address, [[Bool;1];2]) {
       |    return b, c, d, e, f
       |  }
       |}
       |""".stripMargin

  lazy val (script, scriptAst) = Compiler.compileTxScriptFull(scriptStr).toOption.get
}
