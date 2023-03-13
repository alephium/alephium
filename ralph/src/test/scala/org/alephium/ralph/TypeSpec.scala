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

import org.alephium.util.{AlephiumFixture, AlephiumSpec, AVector}

class TypeSpec extends AlephiumSpec {
  it should "return correct signature" in new TypeSignatureFixture {
    contractAst.getFieldsSignature() is
      "Contract Foo(aa:Bool,mut bb:U256,cc:I256,mut dd:ByteVec,ee:Address,ff:[[Bool;1];2])"
    contractAst.getFieldNames() is
      AVector("aa", "bb", "cc", "dd", "ee", "ff")
    contractAst.getFieldTypes() is
      AVector("Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]")
    contractAst.getFieldMutability() is
      AVector(false, true, false, true, false, false)
    contractAst.funcs.map(_.signature) is Seq(
      "@using(preapprovedAssets=true,assetsInContract=true) pub bar(a:Bool,mut b:U256,c:I256,mut d:ByteVec,e:Address,f:[[Bool;1];2])->(U256,I256,ByteVec,Address,[[Bool;1];2])"
    )
    contractAst.funcs.map(_.getArgNames()) is
      Seq(AVector("a", "b", "c", "d", "e", "f"))
    contractAst.funcs.map(_.getArgTypeSignatures()) is
      Seq(AVector("Bool", "U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"))
    contractAst.funcs.map(_.getArgMutability()) is
      Seq(AVector(false, true, false, true, false, false))
    contractAst.funcs.map(_.getReturnSignatures()) is
      Seq(AVector("U256", "I256", "ByteVec", "Address", "[[Bool;1];2]"))
    contractAst.events.map(_.signature) is Seq(
      "event Bar(a:Bool,b:U256,d:ByteVec,e:Address)"
    )
    contractAst.events.map(_.getFieldNames()) is
      Seq(AVector("a", "b", "d", "e"))
    contractAst.events.map(_.getFieldTypeSignatures()) is
      Seq(AVector("Bool", "U256", "ByteVec", "Address"))

    scriptAst.getTemplateVarsSignature() is
      "TxScript Foo(aa:Bool,bb:U256,cc:I256,dd:ByteVec,ee:Address)"
    scriptAst.getTemplateVarsNames() is
      AVector("aa", "bb", "cc", "dd", "ee")
    scriptAst.getTemplateVarsTypes() is
      AVector("Bool", "U256", "I256", "ByteVec", "Address")
    scriptAst.getTemplateVarsMutability() is
      AVector(false, false, false, false, false)
    scriptAst.funcs.map(_.signature) is Seq(
      "@using(preapprovedAssets=true) pub main()->()",
      "pub bar(a:Bool,mut b:U256,c:I256,mut d:ByteVec,e:Address,f:[[Bool;1];2])->(U256,I256,ByteVec,Address,[[Bool;1];2])"
    )
    scriptAst.events.map(_.signature) is Seq.empty
  }
}

trait TypeSignatureFixture extends AlephiumFixture {
  val contractStr =
    s"""
       |Contract Foo(aa: Bool, mut bb: U256, cc: I256, mut dd: ByteVec, ee: Address, ff: [[Bool;1];2]) {
       |  event Bar(a: Bool, b: U256, d: ByteVec, e: Address)
       |
       |  @using(preapprovedAssets = true, assetsInContract = true, updateFields = true)
       |  pub fn bar(a: Bool, mut b: U256, c: I256, mut d: ByteVec, e: Address, f: [[Bool;1];2]) -> (U256, I256, ByteVec, Address, [[Bool;1];2]) {
       |    emit Bar(aa, bb, dd, ee)
       |    emit Debug(`xx`)
       |    transferTokenToSelf!(callerAddress!(), ALPH, 1 alph)
       |    b = 0
       |    bb = 0
       |    d = #
       |    dd = #
       |    return b, c, d, e, f
       |  }
       |}
       |""".stripMargin
  lazy val compiledContract = Compiler.compileContractFull(contractStr).rightValue
  lazy val CompiledContract(contract, contractAst, contractWarnings, _) = compiledContract

  val scriptStr =
    s"""
       |TxScript Foo(aa: Bool, bb: U256, cc: I256, dd: ByteVec, ee: Address) {
       |  return
       |  pub fn bar(a: Bool, mut b: U256, c: I256, mut d: ByteVec, e: Address, f: [[Bool;1];2]) -> (U256, I256, ByteVec, Address, [[Bool;1];2]) {
       |    emit Debug(`xx`)
       |    b = 0
       |    d = #
       |    return b, c, d, e, f
       |  }
       |}
       |""".stripMargin

  lazy val compiledScript = Compiler.compileTxScriptFull(scriptStr).rightValue
  lazy val CompiledScript(script, scriptAst, scriptWarnings, _) = compiledScript
}
