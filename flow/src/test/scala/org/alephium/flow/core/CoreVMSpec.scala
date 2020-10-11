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

package org.alephium.flow.core

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{ChainIndex, ContractOutputRef, TxOutputRef}
import org.alephium.protocol.vm.{StatefulVM, Val}
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AlephiumSpec, AVector, U64}

class VMSpec extends AlephiumSpec {
  it should "not start with private function" in new FlowFixture {
    val input =
      s"""
         |TxScript Foo {
         |  fn add() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(input).toOption.get

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = mine(blockFlow, chainIndex, txScriptOption = Some(script))
    assertThrows[RuntimeException](addAndCheck(blockFlow, block, 1))
  }

  trait CallFixture extends FlowFixture {
    def access: String

    lazy val input0 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  $access fn add(a: U64) -> () {
         |    x = x + a
         |    if (a > 0) {
         |      add(a - 1)
         |    }
         |    return
         |  }
         |}
         |""".stripMargin
    lazy val script0      = Compiler.compileContract(input0).toOption.get
    lazy val initialState = AVector[Val](Val.U64(U64.Zero))

    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val fromLockup = getGenesisLockupScript(chainIndex)
    lazy val txScript0  = StatefulVM.contractCreation(script0, initialState, fromLockup, U64.One)
    lazy val block0 =
      mine(blockFlow, chainIndex, txScriptOption = Some(txScript0), createContract = true)
    lazy val contractOutputRef0 =
      TxOutputRef.unsafe(block0.transactions.head, 1).asInstanceOf[ContractOutputRef]
    lazy val contractKey0 = contractOutputRef0.key

    lazy val input1 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  pub fn add(a: U64) -> () {
         |    x = x + a
         |    if (a > 0) {
         |      add(a - 1)
         |    }
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  pub fn call() -> () {
         |    let foo = Foo(@${contractKey0.toHexString})
         |    foo.add(4)
         |    return
         |  }
         |}
         |""".stripMargin
  }

  it should "not call external private function" in new CallFixture {
    val access: String = ""

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1 = Compiler.compileTxScript(input1, 1).toOption.get
    val block1  = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    assertThrows[RuntimeException](addAndCheck(blockFlow, block1, 2))
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "pub"

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1   = Compiler.compileTxScript(input1, 1).toOption.get
    val newState1 = AVector[Val](Val.U64(U64.unsafe(10)))
    val block1    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractKey0, newState1, contractOutputRef0, numAssets = 4)

    val newState2 = AVector[Val](Val.U64(U64.unsafe(20)))
    val block2    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractKey0, newState2, contractOutputRef0, numAssets = 6)
  }

  it should "use latest worldstate when call external functions" in new FlowFixture {
    val chainIndex = ChainIndex.unsafe(0, 0)

    def createContract(input: String, numAssets: Int, numContracts: Int): ContractOutputRef = {
      val contract     = Compiler.compileContract(input).toOption.get
      val initialState = AVector[Val](Val.U64(U64.Zero))
      val fromLockup   = getGenesisLockupScript(chainIndex)
      val txScript     = StatefulVM.contractCreation(contract, initialState, fromLockup, U64.One)

      val block =
        mine(blockFlow, chainIndex, txScriptOption = Some(txScript), createContract = true)
      val contractOutputRef =
        TxOutputRef.unsafe(block.transactions.head, 1).asInstanceOf[ContractOutputRef]
      val contractKey = contractOutputRef.key

      blockFlow.add(block).isRight is true
      checkState(blockFlow,
                 chainIndex,
                 contractKey,
                 initialState,
                 contractOutputRef,
                 numAssets,
                 numContracts)
      contractOutputRef
    }

    val input0 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  pub fn get() -> (U64) {
         |    return x
         |  }
         |
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |
         |TxContract Bar(mut x: U64) {
         |  pub fn bar(foo: ByteVec) -> (U64) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |""".stripMargin
    val contractOutputRef0 = createContract(input0, 2, 2)
    val contractKey0       = contractOutputRef0.key

    val input1 =
      s"""
         |TxContract Bar(mut x: U64) {
         |  pub fn bar(foo: ByteVec) -> (U64) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |
         |TxContract Foo(mut x: U64) {
         |  pub fn get() -> (U64) {
         |    return x
         |  }
         |
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |
         |""".stripMargin
    val contractOutputRef1 = createContract(input1, 3, 3)
    val contractKey1       = contractOutputRef1.key

    val main =
      s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    let foo = Foo(@${contractKey0.toHexString})
         |    foo.foo(@${contractKey0.toHexString}, @${contractKey1.toHexString})
         |  }
         |}
         |
         |TxContract Foo(mut x: U64) {
         |  pub fn get() -> (U64) {
         |    return x
         |  }
         |
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |""".stripMargin
    val script   = Compiler.compileTxScript(main).toOption.get
    val newState = AVector[Val](Val.U64(U64.unsafe(110)))
    val block    = mine(blockFlow, chainIndex, txScriptOption = Some(script))
    blockFlow.add(block).isRight is true

    val worldState = blockFlow.getBestPersistedTrie(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().toOption.get.length is 3

    checkState(blockFlow,
               chainIndex,
               contractKey0,
               newState,
               contractOutputRef0,
               numAssets    = 5,
               numContracts = 3)
  }
}
