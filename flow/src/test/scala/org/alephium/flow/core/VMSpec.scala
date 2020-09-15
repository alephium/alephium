package org.alephium.flow.core

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{ChainIndex, ContractOutputRef, TxOutputRef}
import org.alephium.protocol.vm.Val
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
    lazy val block0 =
      mine(blockFlow, chainIndex, outputScriptOption = Some(script0 -> initialState))
    lazy val contractOutputRef0 = TxOutputRef.unsafe(block0.transactions.head, 0)
    lazy val contractKey0       = contractOutputRef0.key
    lazy val input1 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  public fn add(a: U64) -> () {
         |    x = x + a
         |    if (a > 0) {
         |      add(a - 1)
         |    }
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  public fn call() -> () {
         |    let foo = Foo(@${contractKey0.toHexString})
         |    foo.add(4)
         |    return
         |  }
         |}
         |""".stripMargin
  }

  it should "not call external private function" in new CallFixture {
    val access: String = ""

    contractOutputRef0 is a[ContractOutputRef]
    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState)

    val script1 = Compiler.compileTxScript(input1, 1).toOption.get
    val block1  = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    assertThrows[RuntimeException](addAndCheck(blockFlow, block1, 2))
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "public"

    contractOutputRef0 is a[ContractOutputRef]
    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState)

    val script1   = Compiler.compileTxScript(input1, 1).toOption.get
    val newState1 = AVector[Val](Val.U64(U64.unsafe(10)))
    val block1    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractKey0, newState1)

    val newState2 = AVector[Val](Val.U64(U64.unsafe(20)))
    val block2    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractKey0, newState2)
  }
}
