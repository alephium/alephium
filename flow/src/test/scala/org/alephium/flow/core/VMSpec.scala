package org.alephium.flow.core

import org.alephium.flow.FlowFixture
import org.alephium.protocol.model.{ChainIndex, ContractOutputRef, TxOutputRef}
import org.alephium.protocol.vm.Val
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.util.{AlephiumSpec, AVector, U64}

class VMSpec extends AlephiumSpec {
  it should "handle contract states" in new FlowFixture {
    val input0 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  public fn add(a: U64) -> () {
         |    x = x + a
         |    return
         |  }
         |}
         |""".stripMargin
    val script0      = Compiler.compileContract(input0).toOption.get
    val initialState = AVector[Val](Val.U64(U64.Zero))

    val chainIndex         = ChainIndex.unsafe(0, 0)
    val block0             = mine(blockFlow, chainIndex, outputScriptOption = Some(script0 -> initialState))
    val contractOutputRef0 = TxOutputRef.unsafe(block0.transactions.head, 0)
    val contractKey0       = contractOutputRef0.key

    contractOutputRef0 is a[ContractOutputRef]
    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState)

    val input1 =
      s"""
         |TxContract Foo(mut x: U64) {
         |  public fn add(a: U64) -> () {
         |    x = x + a
         |    return
         |  }
         |}
         |
         |TxScript Bar {
         |  public fn call() -> () {
         |    let foo = Foo(@${contractKey0.toHexString})
         |    foo.add(1)
         |    return
         |  }
         |}
         |""".stripMargin
    val script1   = Compiler.compileTxScript(input1, 1).toOption.get
    val newState1 = AVector[Val](Val.U64(U64.One))
    val block1    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractKey0, newState1)

    val newState2 = AVector[Val](Val.U64(U64.Two))
    val block2    = mine(blockFlow, chainIndex, txScriptOption = Some(script1))
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractKey0, newState2)
  }
}
