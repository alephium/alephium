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

import akka.util.ByteString
import org.scalatest.Assertion

import org.alephium.flow.FlowFixture
import org.alephium.flow.validation.BlockValidation
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, Hex, U256}

class VMSpec extends AlephiumSpec {
  def contractCreation(code: StatefulContract,
                       initialState: AVector[Val],
                       lockupScript: LockupScript,
                       alfAmount: U256): StatefulScript = {
    val address  = Address(NetworkType.Testnet, lockupScript)
    val codeRaw  = Hex.toHexString(serialize(code))
    val stateRaw = Hex.toHexString(serialize(initialState))
    val scriptRaw =
      s"""
         |TxScript Foo {
         |  pub payable fn main() -> () {
         |    approveAlf!(@${address.toBase58}, ${alfAmount.v})
         |    createContract!(#$codeRaw, #$stateRaw)
         |  }
         |}
         |""".stripMargin
    Compiler.compileTxScript(scriptRaw).toOption.get
  }

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
    val block      = simpleScript(blockFlow, chainIndex, script)
    assertThrows[RuntimeException](addAndCheck(blockFlow, block, 1))
  }

  it should "overflow frame stack" in new FlowFixture {
    val input =
      s"""
         |TxScript Foo {
         |  pub fn main() -> () {
         |    foo(${frameStackMaxSize - 1})
         |  }
         |
         |  fn foo(n: U256) -> () {
         |    if (n > 0) {
         |      foo(n - 1)
         |    }
         |  }
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(input).toOption.get

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = simpleScript(blockFlow, chainIndex, script)
    val tx         = block.transactions.head
    val worldState = blockFlow.getBestCachedTrie(chainIndex.from).toOption.get
    StatefulVM.runTxScript(worldState, tx, tx.unsigned.scriptOpt.get) is Left(StackOverflow)
  }

  trait CallFixture extends FlowFixture {
    def access: String

    lazy val input0 =
      s"""
         |TxContract Foo(mut x: U256) {
         |  $access fn add(a: U256) -> () {
         |    x = x + a
         |    if (a > 0) {
         |      add(a - 1)
         |    }
         |    return
         |  }
         |}
         |""".stripMargin
    lazy val script0      = Compiler.compileContract(input0).toOption.get
    lazy val initialState = AVector[Val](Val.U256(U256.Zero))

    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val fromLockup = getGenesisLockupScript(chainIndex)
    lazy val txScript0  = contractCreation(script0, initialState, fromLockup, U256.One)
    lazy val block0     = payableCall(blockFlow, chainIndex, txScript0)
    lazy val contractOutputRef0 =
      TxOutputRef.unsafe(block0.transactions.head, 0).asInstanceOf[ContractOutputRef]
    lazy val contractKey0 = contractOutputRef0.key

    lazy val input1 =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn add(a: U256) -> () {
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
         |    let foo = Foo(#${contractKey0.toHexString})
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
    val block1  = simpleScript(blockFlow, chainIndex, script1)
    assertThrows[RuntimeException](addAndCheck(blockFlow, block1, 2))
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "pub"

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1   = Compiler.compileTxScript(input1, 1).toOption.get
    val newState1 = AVector[Val](Val.U256(U256.unsafe(10)))
    val block1    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractKey0, newState1, contractOutputRef0, numAssets = 4)

    val newState2 = AVector[Val](Val.U256(U256.unsafe(20)))
    val block2    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractKey0, newState2, contractOutputRef0, numAssets = 6)
  }

  trait ContractFixture extends FlowFixture {
    val chainIndex     = ChainIndex.unsafe(0, 0)
    val genesisLockup  = getGenesisLockupScript(chainIndex)
    val genesisAddress = Address(NetworkType.Testnet, genesisLockup)

    def addAndValidate(blockFlow: BlockFlow, block: Block): Assertion = {
      val blockValidation = BlockValidation.build(blockFlow.brokerConfig, blockFlow.consensusConfig)
      blockValidation.validate(block, blockFlow).isRight is true
      blockFlow.add(block).isRight is true
    }

    def createContract(input: String, initialState: AVector[Val]): ContractOutputRef = {
      val contract = Compiler.compileContract(input).toOption.get
      val txScript = contractCreation(contract, initialState, genesisLockup, U256.One)
      val block    = payableCall(blockFlow, chainIndex, txScript)

      val contractOutputRef =
        TxOutputRef.unsafe(block.transactions.head, 0).asInstanceOf[ContractOutputRef]

      addAndValidate(blockFlow, block)
      contractOutputRef
    }

    def createContract(input: String, numAssets: Int, numContracts: Int): ContractOutputRef = {
      val initialState      = AVector[Val](Val.U256(U256.Zero))
      val contractOutputRef = createContract(input, initialState)

      val contractKey = contractOutputRef.key
      checkState(blockFlow,
                 chainIndex,
                 contractKey,
                 initialState,
                 contractOutputRef,
                 numAssets,
                 numContracts)

      contractOutputRef
    }

    def callTxScript(input: String): Assertion = {
      val script = Compiler.compileTxScript(input).toOption.get
      val block =
        if (script.entryMethod.isPayable) payableCall(blockFlow, chainIndex, script)
        else simpleScript(blockFlow, chainIndex, script)
      addAndValidate(blockFlow, block)
    }

    def callTxScriptMulti(input: Int => String): Block = {
      val block0 = transfer(blockFlow, chainIndex, amount = 2, numReceivers = 10)
      addAndValidate(blockFlow, block0)
      val newAddresses = block0.nonCoinbase.head.unsigned.fixedOutputs.init.map(_.lockupScript)
      val scripts = AVector.tabulate(newAddresses.length) { index =>
        Compiler.compileTxScript(input(index)).fold(throw _, identity)
      }
      val block1 = simpleScriptMulti(blockFlow, chainIndex, newAddresses, scripts)
      addAndValidate(blockFlow, block1)
      block1
    }
  }

  it should "use latest worldstate when call external functions" in new ContractFixture {
    val input0 =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
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
         |TxContract Bar(mut x: U256) {
         |  pub fn bar(foo: ByteVec) -> (U256) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |""".stripMargin
    val contractOutputRef0 = createContract(input0, 2, 2)
    val contractKey0       = contractOutputRef0.key

    val input1 =
      s"""
         |TxContract Bar(mut x: U256) {
         |  pub fn bar(foo: ByteVec) -> (U256) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |
         |TxContract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
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
         |    let foo = Foo(#${contractKey0.toHexString})
         |    foo.foo(#${contractKey0.toHexString}, #${contractKey1.toHexString})
         |  }
         |}
         |
         |TxContract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
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
    val newState = AVector[Val](Val.U256(U256.unsafe(110)))
    val block    = simpleScript(blockFlow, chainIndex, script)
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

  it should "issue new token" in new ContractFixture {
    val input =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub payable fn foo() -> () {
         |    issueToken!(10000000)
         |  }
         |}
         |""".stripMargin
    val contractOutputRef = createContract(input, 2, 2)
    val contractKey       = contractOutputRef.key

    val main =
      s"""
         |TxScript Main {
         |  pub payable fn main() -> () {
         |    let foo = Foo(#${contractKey.toHexString})
         |    foo.foo()
         |  }
         |}
         |
         |TxContract Foo(mut x: U256) {
         |  pub payable fn foo() -> () {
         |    issueToken!(10000000)
         |  }
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(main).toOption.get

    val block0 = payableCall(blockFlow, chainIndex, script)
    blockFlow.add(block0).isRight is true

    val worldState0 = blockFlow.getBestPersistedTrie(chainIndex.from).fold(throw _, identity)
    worldState0.getContractStates().toOption.get.length is 2
    worldState0.getContractOutputs(ByteString.empty).toOption.get.foreach {
      case (ref, output) =>
        if (ref != ContractOutputRef.forMPT) {
          output.tokens.head is (contractKey -> U256.unsafe(10000000))
        }
    }

    val block1 = payableCall(blockFlow, chainIndex, script)
    blockFlow.add(block1).isRight is true

    val worldState1 = blockFlow.getBestPersistedTrie(chainIndex.from).fold(throw _, identity)
    worldState1.getContractStates().toOption.get.length is 2
    worldState1.getContractOutputs(ByteString.empty).toOption.get.foreach {
      case (ref, output) =>
        if (ref != ContractOutputRef.forMPT) {
          output.tokens.head is (contractKey -> U256.unsafe(20000000))
        }
    }
  }

  behavior of "constant product market"

  it should "swap" in new ContractFixture {
    val tokenContract =
      s"""
         |TxContract Token(mut x: U256) {
         |  pub payable fn issue(amount: U256) -> () {
         |    issueToken!(amount)
         |  }
         |
         |  pub payable fn withdraw(address: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
         |  }
         |}
         |""".stripMargin
    val tokenContractKey = createContract(tokenContract, 2, 2).key
    val tokenId          = tokenContractKey

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    let token = Token(#${tokenContractKey.toHexString})
                    |    token.issue(1024)
                    |  }
                    |}
                    |
                    |$tokenContract
                    |""".stripMargin)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    let token = Token(#${tokenContractKey.toHexString})
                    |    token.withdraw(@${genesisAddress.toBase58}, 1024)
                    |  }
                    |}
                    |
                    |$tokenContract
                    |""".stripMargin)

    val swapContract =
      s"""
         |// Simple swap contract purely for testing
         |
         |TxContract Swap(tokenId: ByteVec, mut alfReserve: U256, mut tokenReserve: U256) {
         |  pub payable fn setup() -> () {
         |    issueToken!(10000)
         |  }
         |
         |  pub payable fn addLiquidity(lp: Address, alfAmount: U256, tokenAmount: U256) -> () {
         |    transferAlfToSelf!(lp, alfAmount)
         |    transferTokenToSelf!(lp, tokenId, tokenAmount)
         |    alfReserve = alfAmount
         |    tokenReserve = tokenAmount
         |  }
         |
         |  pub payable fn swapToken(buyer: Address, alfAmount: U256) -> () {
         |    let tokenAmount = tokenReserve - alfReserve * tokenReserve / (alfReserve + alfAmount)
         |    transferAlfToSelf!(buyer, alfAmount)
         |    transferTokenFromSelf!(buyer, tokenId, tokenAmount)
         |    alfReserve = alfReserve + alfAmount
         |    tokenReserve = tokenReserve - tokenAmount
         |  }
         |
         |  pub payable fn swapAlf(buyer: Address, tokenAmount: U256) -> () {
         |    let alfAmount = alfReserve - alfReserve * tokenReserve / (tokenReserve + tokenAmount)
         |    transferTokenToSelf!(buyer, tokenId, tokenAmount)
         |    transferAlfFromSelf!(buyer, alfAmount)
         |    alfReserve = alfReserve - alfAmount
         |    tokenReserve = tokenReserve + tokenAmount
         |  }
         |}
         |""".stripMargin
    val swapContractKey = createContract(
      swapContract,
      AVector[Val](Val.ByteVec.from(tokenId), Val.U256(U256.Zero), Val.U256(U256.Zero))).key

    def checkSwapBalance(alfReserve: Long, tokenReserve: Long) = {
      val worldState = blockFlow.getBestPersistedTrie(chainIndex.from).fold(throw _, identity)
      val output     = worldState.getContractAsset(swapContractKey).toOption.get
      output.amount is alfReserve
      output.tokens.toSeq.toMap.getOrElse(tokenId, U256.Zero) is tokenReserve
    }

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.setup()
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(1, 0)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    approveAlf!(@${genesisAddress.toBase58}, 10)
                    |    approveToken!(@${genesisAddress.toBase58}, #${tokenId.toHexString}, 100)
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.addLiquidity(@${genesisAddress.toBase58}, 10, 100)
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(11, 100)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    approveAlf!(@${genesisAddress.toBase58}, 10)
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.swapToken(@${genesisAddress.toBase58}, 10)
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(21, 50)

    callTxScript(s"""
                    |TxScript Main {
                    |  pub payable fn main() -> () {
                    |    approveToken!(@${genesisAddress.toBase58}, #${tokenId.toHexString}, 50)
                    |    let swap = Swap(#${swapContractKey.toHexString})
                    |    swap.swapAlf(@${genesisAddress.toBase58}, 50)
                    |  }
                    |}
                    |
                    |$swapContract
                    |""".stripMargin)
    checkSwapBalance(11, 100)
  }

  behavior of "random execution"

  it should "execute tx in random order" in new ContractFixture {
    val testContract =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn foo(y: U256) -> () {
         |    x = x * 10 + y
         |  }
         |}
         |""".stripMargin
    val contractKey = createContract(testContract, 2, 2).key

    val block = callTxScriptMulti(index => s"""
         |TxScript Main {
         |  pub fn main() -> () {
         |    let foo = Foo(#${contractKey.toHexString})
         |    foo.foo($index)
         |  }
         |}
         |
         |$testContract
         |""".stripMargin)

    val expected      = block.getNonCoinbaseExecutionOrder.fold(0L)(_ * 10 + _)
    val worldState    = blockFlow.getBestPersistedTrie(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(contractKey).fold(throw _, identity)
    contractState.fields is AVector[Val](Val.U256(U256.unsafe(expected)))
  }
}
