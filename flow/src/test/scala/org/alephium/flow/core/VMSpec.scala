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

import java.math.BigInteger

import scala.language.implicitConversions

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto._
import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.MemPool.AddedToSharedPool
import org.alephium.flow.validation.{TxScriptExeFailed, TxValidation}
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Compiler
import org.alephium.serde.{deserialize, serialize, Serde}
import org.alephium.util._

// scalastyle:off file.size.limit method.length number.of.methods
class VMSpec extends AlephiumSpec {
  implicit def gasBox(n: Int): GasBox = GasBox.unsafe(n)

  it should "not start with private function" in new ContractFixture {
    val input =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Foo {
         |  return
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val script      = Compiler.compileTxScript(input).rightValue
    val errorScript = StatefulScript.unsafe(AVector(script.methods.head.copy(isPublic = false)))
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, errorScript)).getMessage is
      s"Right(TxScriptExeFailed($ExternalPrivateMethodCall))"
  }

  it should "overflow frame stack" in new FlowFixture {
    val input =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Foo {
         |  foo(${frameStackMaxSize - 1})
         |
         |  fn foo(n: U256) -> () {
         |    if (n > 0) {
         |      foo(n - 1)
         |    }
         |  }
         |}
         |""".stripMargin
    val script = Compiler.compileTxScript(input).rightValue

    val chainIndex = ChainIndex.unsafe(0, 0)
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script)).getMessage is
      s"Right(TxScriptExeFailed($OutOfGas))"
    intercept[AssertionError](
      simpleScript(blockFlow, chainIndex, script, gas = 400000)
    ).getMessage is
      s"Right(TxScriptExeFailed($StackOverflow))"
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
    lazy val script0      = Compiler.compileContract(input0).rightValue
    lazy val initialState = AVector[Val](Val.U256(U256.Zero))

    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val fromLockup = getGenesisLockupScript(chainIndex)
    lazy val txScript0  = contractCreation(script0, initialState, fromLockup, ALPH.alph(1))
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
         |@using(preapprovedAssets = false)
         |TxScript Bar {
         |  let foo = Foo(#${contractKey0.toHexString})
         |  foo.add(4)
         |  return
         |}
         |""".stripMargin
  }

  it should "not call external private function" in new CallFixture {
    val access: String = ""

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1 = Compiler.compileTxScript(input1, 1).rightValue
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script1)).getMessage is
      s"Right(TxScriptExeFailed($ExternalPrivateMethodCall))"
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "pub"

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractKey0, initialState, contractOutputRef0)

    val script1   = Compiler.compileTxScript(input1, 1).rightValue
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
    val genesisAddress = Address.Asset(genesisLockup)

    def createContract(
        input: String,
        initialState: AVector[Val],
        tokenAmount: Option[U256] = None,
        initialAttoAlphAmount: U256 = minimalAlphInContract
    ): ContractOutputRef = {
      val contract = Compiler.compileContract(input).rightValue
      val txScript =
        contractCreation(contract, initialState, genesisLockup, initialAttoAlphAmount, tokenAmount)
      val block = payableCall(blockFlow, chainIndex, txScript)

      val contractOutputRef =
        TxOutputRef.unsafe(block.transactions.head, 0).asInstanceOf[ContractOutputRef]

      deserialize[StatefulContract.HalfDecoded](serialize(contract.toHalfDecoded())).rightValue
        .toContract() isE contract
      addAndCheck(blockFlow, block)
      contractOutputRef
    }

    def createContractAndCheckState(
        input: String,
        numAssets: Int,
        numContracts: Int,
        initialState: AVector[Val] = AVector[Val](Val.U256(U256.Zero)),
        tokenAmount: Option[U256] = None,
        initialAttoAlphAmount: U256 = minimalAlphInContract
    ): ContractOutputRef = {
      val contractOutputRef =
        createContract(input, initialState, tokenAmount, initialAttoAlphAmount)

      val contractKey = contractOutputRef.key
      checkState(
        blockFlow,
        chainIndex,
        contractKey,
        initialState,
        contractOutputRef,
        numAssets,
        numContracts
      )

      contractOutputRef
    }

    def callTxScript(input: String): Block = {
      val script = Compiler.compileTxScript(input).rightValue
      script.toTemplateString() is Hex.toHexString(serialize(script))
      val block =
        if (script.entryMethod.usePreapprovedAssets) {
          payableCall(blockFlow, chainIndex, script)
        } else {
          simpleScript(blockFlow, chainIndex, script)
        }
      addAndCheck(blockFlow, block)
      block
    }

    def callTxScriptMulti(input: Int => String): Block = {
      val block0 = transfer(blockFlow, chainIndex, numReceivers = 10)
      addAndCheck(blockFlow, block0)
      val newAddresses = block0.nonCoinbase.head.unsigned.fixedOutputs.init.map(_.lockupScript)
      val scripts = AVector.tabulate(newAddresses.length) { index =>
        Compiler.compileTxScript(input(index)).fold(throw _, identity)
      }
      val block1 = simpleScriptMulti(blockFlow, chainIndex, newAddresses, scripts)
      addAndCheck(blockFlow, block1)
      block1
    }

    def testSimpleScript(main: String) = {
      val script = Compiler.compileTxScript(main).rightValue
      val block  = simpleScript(blockFlow, chainIndex, script)
      addAndCheck(blockFlow, block)
    }

    def failSimpleScript(main: String, failure: ExeFailure) = {
      val script = Compiler.compileTxScript(main).rightValue
      intercept[AssertionError](simpleScript(blockFlow, chainIndex, script)).getMessage is
        s"Right(TxScriptExeFailed($failure))"
    }

    def failCallTxScript(script: String, failure: ExeFailure) = {
      intercept[AssertionError](callTxScript(script)).getMessage is
        s"Right(TxScriptExeFailed($failure))"
    }

    def fail(blockFlow: BlockFlow, block: Block, failure: ExeFailure): Assertion = {
      intercept[AssertionError](addAndCheck(blockFlow, block)).getMessage is
        s"Right(ExistInvalidTx(TxScriptExeFailed($failure)))"
    }

    def fail(
        blockFlow: BlockFlow,
        chainIndex: ChainIndex,
        script: StatefulScript,
        failure: ExeFailure
    ) = {
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage is
        s"Right(TxScriptExeFailed($failure))"
    }

    def checkContractState(
        contractId: String,
        contractAssetRef: ContractOutputRef,
        existed: Boolean
    ): Assertion = {
      val worldState  = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractKey = Hash.from(Hex.from(contractId).get).get
      worldState.contractState.exists(contractKey) isE existed
      worldState.outputState.exists(contractAssetRef) isE existed
    }
  }

  it should "disallow loading upgraded contract in current tx" in new ContractFixture {
    val fooV1Code =
      s"""
         |TxContract FooV1() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    val fooV1 = Compiler.compileContract(fooV1Code).rightValue

    val fooV0Code =
      s"""
         |TxContract FooV0() {
         |  pub fn upgrade() -> () {
         |    migrate!(#${Hex.toHexString(serialize(fooV1))})
         |  }
         |}
         |""".stripMargin
    val fooContractId = createContract(fooV0Code, AVector.empty).key.toHexString

    val script =
      s"""
         |TxScript Main {
         |  let fooV0 = FooV0(#$fooContractId)
         |  fooV0.upgrade()
         |  let fooV1 = FooV1(#$fooContractId)
         |  fooV1.foo()
         |}
         |
         |$fooV0Code
         |
         |$fooV1Code
         |""".stripMargin

    intercept[AssertionError](callTxScript(script)).getMessage.startsWith(
      "Right(TxScriptExeFailed(ContractLoadDisallowed"
    ) is true
  }

  it should "burn token" in new ContractFixture {
    val contract =
      s"""
         |TxContract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn mint() -> () {
         |    transferTokenFromSelf!(@$genesisAddress, selfTokenId!(), 2 alph)
         |  }
         |
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn burn() -> () {
         |    burnToken!(@$genesisAddress, selfTokenId!(), 1 alph)
         |    burnToken!(selfAddress!(), selfTokenId!(), 1 alph)
         |  }
         |}
         |""".stripMargin
    val contractId = createContract(contract, AVector.empty, Some(ALPH.alph(5))).key

    val mint =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.mint()
         |}
         |
         |$contract
         |""".stripMargin
    callTxScript(mint)
    val worldState0    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val contractAsset0 = worldState0.getContractAsset(contractId).rightValue
    contractAsset0.lockupScript is LockupScript.p2c(contractId)
    contractAsset0.tokens is AVector(contractId -> ALPH.alph(3))
    val tokenAmount0 = getTokenBalance(blockFlow, genesisAddress.lockupScript, contractId)
    tokenAmount0 is ALPH.alph(2)

    val burn =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.burn{@$genesisAddress -> #${contractId.toHexString}: 2 alph}()
         |}
         |
         |$contract
         |""".stripMargin
    callTxScript(burn)
    val worldState1    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val contractAsset1 = worldState1.getContractAsset(contractId).rightValue
    contractAsset1.lockupScript is LockupScript.p2c(contractId)
    contractAsset1.tokens is AVector(contractId -> ALPH.alph(2))
    val tokenAmount1 = getTokenBalance(blockFlow, genesisAddress.lockupScript, contractId)
    tokenAmount1 is ALPH.alph(1)
  }

  it should "lock assets" in new ContractFixture {
    val token =
      s"""
         |TxContract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn mint() -> () {
         |    transferTokenFromSelf!(@$genesisAddress, selfTokenId!(), 10 alph)
         |  }
         |}
         |""".stripMargin

    import org.alephium.protocol.model.tokenIdOrder
    val _tokenId0               = createContract(token, AVector.empty, ALPH.alph(100)).key
    val _tokenId1               = createContract(token, AVector.empty, ALPH.alph(100)).key
    val Seq(tokenId0, tokenId1) = Seq(_tokenId0, _tokenId1).sorted
    val tokenId0Hex             = tokenId0.toHexString
    val tokenId1Hex             = tokenId1.toHexString

    val mint =
      s"""
         |TxScript Main {
         |  let token0 = Foo(#$tokenId0Hex)
         |  token0.mint()
         |  let token1 = Foo(#$tokenId1Hex)
         |  token1.mint()
         |}
         |
         |$token
         |""".stripMargin
    callTxScript(mint)

    val lock =
      s"""
         |TxScript Main {
         |  let timestamp0 = 1000
         |  let timestamp1 = 2000
         |  let timestamp2 = 3000
         |
         |  lockApprovedAssets!{ @$genesisAddress -> 0.01 alph }(@$genesisAddress, timestamp0)
         |
         |  lockApprovedAssets!{
         |    @$genesisAddress -> 0.02 alph, #$tokenId0Hex: 0.03 alph
         |  }(@$genesisAddress, timestamp1)
         |
         |  lockApprovedAssets!{
         |    @$genesisAddress -> 0.04 alph, #$tokenId0Hex: 0.05 alph, #$tokenId1Hex: 0.06 alph
         |  }(@$genesisAddress, timestamp2)
         |}
         |
         |$token
         |""".stripMargin

    val tx = callTxScript(lock).nonCoinbase(0)
    tx.generatedOutputs(0) is AssetOutput(
      ALPH.cent(1),
      genesisAddress.lockupScript,
      TimeStamp.unsafe(1000),
      AVector.empty,
      ByteString.empty
    )
    tx.generatedOutputs(1) is AssetOutput(
      ALPH.cent(2),
      genesisAddress.lockupScript,
      TimeStamp.unsafe(2000),
      AVector(tokenId0 -> ALPH.cent(3)),
      ByteString.empty
    )
    tx.generatedOutputs(2) is AssetOutput(
      ALPH.cent(4),
      genesisAddress.lockupScript,
      TimeStamp.unsafe(3000),
      AVector(tokenId0 -> ALPH.cent(5), tokenId1 -> ALPH.cent(6)),
      ByteString.empty
    )
  }

  it should "not use up contract assets" in new ContractFixture {
    val input =
      """
        |TxContract Foo() {
        |  @using(assetsInContract = true)
        |  pub fn foo(address: Address) -> () {
        |    transferAlphFromSelf!(address, alphRemaining!(selfAddress!()))
        |  }
        |}
        |""".stripMargin

    val contractId = createContractAndCheckState(input, 2, 2, AVector.empty).key

    val main =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.foo(@${genesisAddress.toBase58})
         |}
         |
         |$input
         |""".stripMargin

    val script = Compiler.compileTxScript(main).rightValue
    fail(blockFlow, chainIndex, script, EmptyContractAsset)
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
    val contractOutputRef0 = createContractAndCheckState(input0, 2, 2)
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
    val contractOutputRef1 = createContractAndCheckState(input1, 3, 3)
    val contractKey1       = contractOutputRef1.key

    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let foo = Foo(#${contractKey0.toHexString})
         |  foo.foo(#${contractKey0.toHexString}, #${contractKey1.toHexString})
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
    val newState = AVector[Val](Val.U256(U256.unsafe(110)))
    testSimpleScript(main)

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().rightValue.length is 3

    checkState(
      blockFlow,
      chainIndex,
      contractKey0,
      newState,
      contractOutputRef0,
      numAssets = 5,
      numContracts = 3
    )
  }

  it should "issue new token" in new ContractFixture {
    val input =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contractOutputRef = createContractAndCheckState(input, 2, 2, tokenAmount = Some(10000000))
    val contractKey       = contractOutputRef.key

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().rightValue.length is 2
    worldState.getContractOutputs(ByteString.empty, Int.MaxValue).rightValue.foreach {
      case (ref, output) =>
        if (ref != ContractOutputRef.forSMT) {
          output.tokens.head is (contractKey -> U256.unsafe(10000000))
        }
    }
  }

  // scalastyle:off method.length
  it should "test operators" in new ContractFixture {
    // scalastyle:off no.equal
    def expect(out: Int) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Inverse {
         |  let x = 10973
         |  let mut y = 1
         |  let mut i = 0
         |  while (i <= 8) {
         |    y = y ⊗ (2 ⊖ x ⊗ y)
         |    i = i + 1
         |  }
         |  let r = x ⊗ y
         |  assert!(r == $out)
         |
         |  test()
         |
         |  fn test() -> () {
         |    assert!((33 + 2 - 3) * 5 / 7 % 11 == 0)
         |
         |    let x = 0
         |    let y = 1
         |    assert!(x << 1 == 0)
         |    assert!(x >> 1 == 0)
         |    assert!(y << 1 == 2)
         |    assert!(y >> 1 == 0)
         |    assert!(y << 255 != 0)
         |    assert!(y << 256 == 0)
         |    assert!(x & x == 0)
         |    assert!(x & y == 0)
         |    assert!(y & y == 1)
         |    assert!(x | x == 0)
         |    assert!(x | y == 1)
         |    assert!(y | y == 1)
         |    assert!(x ^ x == 0)
         |    assert!(x ^ y == 1)
         |    assert!(y ^ y == 0)
         |
         |    assert!((x < y) == true)
         |    assert!((x <= y) == true)
         |    assert!((x < x) == false)
         |    assert!((x <= x) == true)
         |    assert!((x > y) == false)
         |    assert!((x >= y) == false)
         |    assert!((x > x) == false)
         |    assert!((x >= x) == true)
         |
         |    assert!((true && true) == true)
         |    assert!((true && false) == false)
         |    assert!((false && false) == false)
         |    assert!((true || true) == true)
         |    assert!((true || false) == true)
         |    assert!((false || false) == false)
         |
         |    assert!(!true == false)
         |    assert!(!false == true)
         |  }
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    testSimpleScript(expect(1))
    failSimpleScript(expect(2), AssertionFailed)
  }
  // scalastyle:on method.length

  // scalastyle:off no.equal
  it should "test ByteVec instructions" in new ContractFixture {
    def encode[T: Serde](t: T): String = Hex.toHexString(serialize(t))

    val i256    = UnsecureRandom.nextI256()
    val u256    = UnsecureRandom.nextU256()
    val address = Address.from(LockupScript.p2c(Hash.random))
    val bytes0  = Hex.toHexString(Hash.random.bytes)
    val bytes1  = Hex.toHexString(Hash.random.bytes)

    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript ByteVecTest {
         |  assert!(byteVec!(true) == #${encode(true)})
         |  assert!(byteVec!(false) == #${encode(false)})
         |  assert!(byteVec!(${i256}i) == #${encode(i256)})
         |  assert!(byteVec!(${u256}) == #${encode(u256)})
         |  assert!(byteVec!(@${address.toBase58}) == #${encode(address.lockupScript)})
         |  assert!(# ++ #$bytes0 == #$bytes0)
         |  assert!(#$bytes0 ++ # == #$bytes0)
         |  assert!((#${bytes0} ++ #${bytes1}) == #${bytes0 ++ bytes1})
         |  assert!(size!(byteVec!(true)) == 1)
         |  assert!(size!(byteVec!(false)) == 1)
         |  assert!(size!(byteVec!(@${address.toBase58})) == 33)
         |  assert!(size!(#${bytes0} ++ #${bytes1}) == 64)
         |  assert!(zeros!(2) == #0000)
         |  assert!(nullAddress!() == @${Address.contract(ContractId.zero)})
         |  assert!(nullAddress!() == @tgx7VNFoP9DJiFMFgXXtafQZkUvyEdDHT9ryamHJYrjq)
         |}
         |""".stripMargin

    testSimpleScript(main)
  }

  it should "test CopyCreateContractWithToken instruction" in new ContractFixture {
    val fooContract =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |  }
         |}
         |""".stripMargin

    val contractOutputRef = createContract(fooContract, AVector.empty)
    val contractId        = contractOutputRef.key.toHexString

    val encodedState = Hex.toHexString(serialize[AVector[Val]](AVector.empty))
    val tokenAmount  = ALPH.oneNanoAlph
    val script =
      s"""
         |TxScript Main {
         |  copyCreateContractWithToken!{ @$genesisAddress -> 1 alph }(#$contractId, #$encodedState, ${tokenAmount.v})
         |}
         |""".stripMargin

    val block          = callTxScript(script)
    val transaction    = block.nonCoinbase.head
    val contractOutput = transaction.generatedOutputs(0).asInstanceOf[ContractOutput]
    val tokenId        = contractOutput.lockupScript.contractId
    contractOutput.tokens is AVector((tokenId, tokenAmount))
  }

  // scalastyle:off no.equal
  it should "test contract instructions" in new ContractFixture {
    def createContract(input: String): (String, String, String, String) = {
      val contractId    = createContract(input, initialState = AVector.empty).key
      val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      val contractState = worldState.getContractState(contractId).rightValue
      val address       = Address.Contract(LockupScript.p2c(contractId)).toBase58
      (
        contractId.toHexString,
        address,
        contractState.initialStateHash.toHexString,
        contractState.codeHash.toHexString
      )
    }

    val foo =
      s"""
         |TxContract Foo() {
         |  pub fn foo(fooId: ByteVec, fooHash: ByteVec, fooCodeHash: ByteVec, barId: ByteVec, barHash: ByteVec, barCodeHash: ByteVec, barAddress: Address) -> () {
         |    assert!(selfContractId!() == fooId)
         |    assert!(contractInitialStateHash!(fooId) == fooHash)
         |    assert!(contractInitialStateHash!(barId) == barHash)
         |    assert!(contractCodeHash!(fooId) == fooCodeHash)
         |    assert!(contractCodeHash!(barId) == barCodeHash)
         |    assert!(callerContractId!() == barId)
         |    assert!(callerAddress!() == barAddress)
         |    assert!(callerInitialStateHash!() == barHash)
         |    assert!(callerCodeHash!() == barCodeHash)
         |    assert!(isCalledFromTxScript!() == false)
         |    assert!(isAssetAddress!(barAddress) == false)
         |    assert!(isContractAddress!(barAddress) == true)
         |  }
         |}
         |""".stripMargin
    val (fooId, _, fooHash, fooCodeHash) = createContract(foo)

    val bar =
      s"""
         |TxContract Bar() {
         |  @using(preapprovedAssets = true)
         |  pub fn bar(fooId: ByteVec, fooHash: ByteVec, fooCodeHash: ByteVec, barId: ByteVec, barHash: ByteVec, barCodeHash: ByteVec, barAddress: Address) -> () {
         |    assert!(selfContractId!() == barId)
         |    assert!(selfAddress!() == barAddress)
         |    assert!(contractInitialStateHash!(fooId) == fooHash)
         |    assert!(contractInitialStateHash!(barId) == barHash)
         |    Foo(#$fooId).foo(fooId, fooHash, fooCodeHash, barId, barHash, barCodeHash, barAddress)
         |    assert!(isCalledFromTxScript!() == true)
         |    assert!(isPaying!(@$genesisAddress) == true)
         |    assert!(isPaying!(selfAddress!()) == false)
         |    assert!(isAssetAddress!(@$genesisAddress) == true)
         |    assert!(isContractAddress!(@$genesisAddress) == false)
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val (barId, barAddress, barHash, barCodeHash) = createContract(bar)

    def main(state: String) =
      s"""
         |TxScript Main {
         |  Bar(#$barId).bar{ @$genesisAddress -> 1 alph }(#$fooId, #$fooHash, #$fooCodeHash, #$barId, #$barHash, #$barCodeHash, @$barAddress)
         |  copyCreateContract!{ @$genesisAddress -> 1 alph }(#$fooId, #$state)
         |  assert!(isPaying!(@$genesisAddress) == true)
         |}
         |
         |$bar
         |""".stripMargin

    {
      val script = Compiler.compileTxScript(main("00")).rightValue
      val block  = payableCall(blockFlow, chainIndex, script)
      addAndCheck(blockFlow, block)
    }

    {
      info("Try to create a new contract with invalid number of fields")
      val script = Compiler.compileTxScript(main("010001")).rightValue
      fail(blockFlow, chainIndex, script, InvalidFieldLength)
    }
  }

  it should "test contractIdToAddress instruction" in new ContractFixture {
    def success(): String = {
      val contractId       = Hash.generate
      val address: Address = Address.contract(contractId)
      val addressHex       = Hex.toHexString(serialize(address.lockupScript))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let address = contractIdToAddress!(#${contractId.toHexString})
         |  assert!(byteVecToAddress!(#$addressHex) == address)
         |}
         |""".stripMargin
    }

    testSimpleScript(success())

    def failure(length: Int): String = {
      val bs         = ByteString(Gen.listOfN(length, arbitrary[Byte]).sample.get)
      val contractId = new Blake2b(bs)
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  contractIdToAddress!(#${contractId.toHexString})
         |}
         |""".stripMargin
    }

    failSimpleScript(failure(31), InvalidContractId)
    failSimpleScript(failure(33), InvalidContractId)
  }

  trait DestroyFixture extends ContractFixture {
    def prepareContract(
        contract: String,
        initialState: AVector[Val] = AVector.empty,
        initialAttoAlphAmount: U256 = minimalAlphInContract
    ): (String, ContractOutputRef) = {
      val contractId =
        createContract(contract, initialState, initialAttoAlphAmount = initialAttoAlphAmount).key
      val worldState       = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractAssetRef = worldState.getContractState(contractId).rightValue.contractOutputRef
      contractId.toHexString -> contractAssetRef
    }
  }

  it should "destroy contract" in new DestroyFixture {
    val foo =
      s"""
         |TxContract Foo(mut x: U256) {
         |  @using(assetsInContract = true)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    x = x + 1
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo, AVector(Val.U256(0)))
    checkContractState(fooId, fooAssetRef, true)

    def main(targetAddress: String) =
      s"""
         |TxScript Main {
         |  Foo(#$fooId).destroy(@$targetAddress)
         |}
         |
         |$foo
         |""".stripMargin

    {
      info("Destroy a contract with contract address")
      val address = Address.Contract(LockupScript.P2C(Hash.generate))
      val script  = Compiler.compileTxScript(main(address.toBase58)).rightValue
      fail(blockFlow, chainIndex, script, InvalidAddressTypeInContractDestroy)
      checkContractState(fooId, fooAssetRef, true)
    }

    {
      info("Destroy a contract twice, this should fail")
      val main =
        s"""
           |TxScript Main {
           |  Foo(#$fooId).destroy(@${genesisAddress.toBase58})
           |  Foo(#$fooId).destroy(@${genesisAddress.toBase58})
           |}
           |
           |$foo
           |""".stripMargin
      val script = Compiler.compileTxScript(main).rightValue
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
        .startsWith("Right(TxScriptExeFailed(NonExistContract") is true
      checkContractState(fooId, fooAssetRef, true) // None of the two destruction will take place
    }

    {
      info("Destroy a contract properly")
      callTxScript(main(genesisAddress.toBase58))
      checkContractState(fooId, fooAssetRef, false)
    }
  }

  it should "not destroy a contract after approving assets" in new DestroyFixture {
    def buildFoo(useAssetsInContract: Boolean) =
      s"""
         |TxContract Foo() {
         |  @using(assetsInContract = $useAssetsInContract)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    approveAlph!(selfAddress!(), 2 alph)
         |    destroySelf!(targetAddress)
         |  }
         |}
         |""".stripMargin
    def main(fooId: Hash, foo: String) =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).destroy(@$genesisAddress)
         |}
         |$foo
         |""".stripMargin
    def test(useAssetsInContract: Boolean, error: ExeFailure) = {
      val foo   = buildFoo(useAssetsInContract)
      val fooId = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(10)).key
      failCallTxScript(main(fooId, foo), error)
    }

    test(useAssetsInContract = true, ContractAssetAlreadyFlushed)
    test(useAssetsInContract = false, NoBalanceAvailable)
  }

  it should "migrate contract" in new DestroyFixture {
    val fooV1 =
      s"""
         |TxContract Foo(x: Bool) {
         |  pub fn foo(code: ByteVec, changeState: Bool) -> () {
         |    // in practice, we should check the permission for migration
         |    if (!changeState) {
         |      migrate!(code)
         |    } else {
         |      migrateWithFields!(code, #010000)
         |    }
         |  }
         |
         |  pub fn checkX(expected: Bool) -> () {
         |    assert!(x == expected)
         |  }
         |}
         |""".stripMargin
    val (fooId, _) = prepareContract(fooV1, AVector[Val](Val.True))
    val fooV2 =
      s"""
         |TxContract Foo(x: Bool) {
         |  pub fn foo(code: ByteVec, changeState: Bool) -> () {
         |    if (changeState) {
         |      migrateWithFields!(code, #010000)
         |    } else {
         |      migrate!(code)
         |    }
         |  }
         |
         |  pub fn checkX(expected: Bool) -> () {
         |    assert!(x == expected)
         |  }
         |}
         |""".stripMargin
    val fooV2Code = Compiler.compileContract(fooV2).rightValue

    def upgrade(changeState: String): String =
      s"""
         |TxScript Main {
         |  let foo = Foo(#$fooId)
         |  foo.foo(#${Hex.toHexString(serialize(fooV2Code))}, ${changeState})
         |}
         |
         |$fooV1
         |""".stripMargin

    def checkState(expected: String): String =
      s"""
         |TxScript Main {
         |  let foo = Foo(#$fooId)
         |  foo.checkX($expected)
         |}
         |
         |$fooV1
         |""".stripMargin

    {
      info("migrate without state change")
      callTxScript(upgrade("false"))
      callTxScript(checkState("true"))
      val worldState  = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractKey = Hash.from(Hex.from(fooId).get).get
      val obj         = worldState.getContractObj(contractKey).rightValue
      obj.contractId is contractKey
      obj.code is fooV2Code.toHalfDecoded()
      obj.initialFields is AVector[Val](Val.True)
    }

    {
      info("migrate with state change")
      callTxScript(upgrade("true"))
      callTxScript(checkState("false"))
      val worldState  = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractKey = Hash.from(Hex.from(fooId).get).get
      val obj         = worldState.getContractObj(contractKey).rightValue
      obj.contractId is contractKey
      obj.code is fooV2Code.toHalfDecoded()
      obj.initialFields is AVector[Val](Val.False)
    }
  }

  it should "call contract destroy function from another contract" in new DestroyFixture {
    val foo =
      s"""
         |TxContract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo)
    checkContractState(fooId, fooAssetRef, true)

    val bar =
      s"""
         |TxContract Bar() {
         |  @using(assetsInContract = true)
         |  pub fn bar(targetAddress: Address) -> () {
         |    transferAlphToSelf!(selfAddress!(), 0) // dirty hack
         |    Foo(#$fooId).destroy(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector.empty).key.toHexString

    val main =
      s"""
         |TxScript Main {
         |  Bar(#$barId).bar(@${genesisAddress.toBase58})
         |}
         |
         |$bar
         |""".stripMargin

    callTxScript(main)
    checkContractState(fooId, fooAssetRef, false)
  }

  it should "not call contract destroy function from the same contract" in new DestroyFixture {
    val foo =
      s"""
         |TxContract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo(targetAddress: Address) -> () {
         |    approveAlph!(selfAddress!(), alphRemaining!(selfAddress!()))
         |    destroy(targetAddress)
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo)
    checkContractState(fooId, fooAssetRef, true)

    val main =
      s"""
         |TxScript Main {
         |  Foo(#$fooId).foo(@${genesisAddress.toBase58})
         |}
         |
         |$foo
         |""".stripMargin
    val script = Compiler.compileTxScript(main).rightValue
    val errorMessage =
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
    errorMessage.startsWith("Right(TxScriptExeFailed(UncaughtKeyNotFoundError") is true
  }

  it should "fetch block env" in new ContractFixture {
    def main(latestHeader: BlockHeader) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(networkId!() == #02)
         |  assert!(blockTimeStamp!() >= ${latestHeader.timestamp.millis})
         |  assert!(blockTarget!() == ${latestHeader.target.value})
         |}
         |""".stripMargin

    def test() = {
      val latestTip    = blockFlow.getHeaderChain(chainIndex).getBestTipUnsafe()
      val latestHeader = blockFlow.getBlockHeaderUnsafe(latestTip)
      testSimpleScript(main(latestHeader))
    }

    // we test with three new blocks
    test()
    test()
    test()
  }

  it should "fetch tx env" in new ContractFixture {
    val zeroId = Hash.zero
    def main(index: Int) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript TxEnv {
         |  assert!(txId!() != #${zeroId.toHexString})
         |  assert!(txInputAddress!($index) == @${genesisAddress.toBase58})
         |  assert!(txInputsSize!() == 1)
         |}
         |""".stripMargin
    testSimpleScript(main(0))
    failSimpleScript(main(1), InvalidTxInputIndex)
  }

  it should "test dust amount" in new ContractFixture {
    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(dustAmount!() == 0.001 alph)
         |  assert!(dustAmount!() == $dustUtxoAmount)
         |}
         |""".stripMargin
    testSimpleScript(main)
  }

  // scalastyle:off regex
  it should "test hash built-ins" in new ContractFixture {
    val input = Hex.toHexString(ByteString.fromString("Hello World1"))
    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(blake2b!(#$input) == #8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b37a)
         |  assert!(blake2b!(#$input) != #8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b370)
         |  assert!(keccak256!(#$input) == #2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E3C)
         |  assert!(keccak256!(#$input) != #2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E30)
         |  assert!(sha256!(#$input) == #6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A9A)
         |  assert!(sha256!(#$input) != #6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A90)
         |  assert!(sha3!(#$input) == #f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed743)
         |  assert!(sha3!(#$input) != #f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed740)
         |}
         |""".stripMargin
    testSimpleScript(main)
  }

  // scalastyle:off no.equal
  it should "test signature built-ins" in new ContractFixture {
    val zero                     = Hash.zero.toHexString
    val (p256Pri, p256Pub)       = SecP256K1.generatePriPub()
    val p256Sig                  = SecP256K1.sign(Hash.zero.bytes, p256Pri).toHexString
    val (ed25519Pri, ed25519Pub) = ED25519.generatePriPub()
    val ed25519Sig               = ED25519.sign(Hash.zero.bytes, ed25519Pri).toHexString
    def main(p256Sig: String, ed25519Sig: String) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  verifySecP256K1!(#$zero, #${p256Pub.toHexString}, #$p256Sig)
         |  verifyED25519!(#$zero, #${ed25519Pub.toHexString}, #$ed25519Sig)
         |}
         |""".stripMargin
    testSimpleScript(main(p256Sig, ed25519Sig))
    failSimpleScript(main(SecP256K1Signature.zero.toHexString, ed25519Sig), InvalidSignature)
    failSimpleScript(main(p256Sig, ED25519Signature.zero.toHexString), InvalidSignature)
  }

  it should "test eth ecrecover" in new ContractFixture with EthEcRecoverFixture {
    def main(messageHash: ByteString, signature: ByteString, address: ByteString) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let address = ethEcRecover!(#${Hex.toHexString(messageHash)},
         |    #${Hex.toHexString(signature)})
         |  assert!(address == #${Hex.toHexString(address)})
         |}
         |""".stripMargin
    testSimpleScript(main(messageHash.bytes, signature, address))
    failSimpleScript(main(signature, messageHash.bytes, address), FailedInRecoverEthAddress)
    failSimpleScript(
      main(messageHash.bytes, signature, Hash.random.bytes.take(20)),
      AssertionFailed
    )
  }

  it should "test locktime built-ins" in new ContractFixture {
    // avoid genesis blocks due to genesis timestamp
    val block = transfer(blockFlow, chainIndex)
    addAndCheck(blockFlow, block)

    def main(absoluteTimeLock: TimeStamp, relativeTimeLock: Duration, txIndex: Int) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  verifyAbsoluteLocktime!(${absoluteTimeLock.millis})
         |  verifyRelativeLocktime!(${txIndex}, ${relativeTimeLock.millis})
         |}
         |""".stripMargin
    testSimpleScript(main(block.timestamp, Duration.unsafe(1), 0))
    failSimpleScript(main(block.timestamp, Duration.unsafe(1), 1), InvalidTxInputIndex)
    failSimpleScript(
      main(TimeStamp.now() + Duration.ofMinutesUnsafe(1), Duration.unsafe(1), 0),
      AbsoluteLockTimeVerificationFailed
    )
    failSimpleScript(
      main(block.timestamp, Duration.ofMinutesUnsafe(1), 0),
      RelativeLockTimeVerificationFailed
    )
  }

  it should "test u256 to bytes" in new ContractFixture {
    def genNumber(size: Int): BigInteger =
      BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE)
    def main(func: String, size: Int): String = {
      val number = U256.from(genNumber(size)).getOrElse(U256.MaxValue)
      val hex    = Hex.toHexString(IndexedSeq.fill(size)(0xff.toByte))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!($func($number) == #$hex)
         |}
         |""".stripMargin
    }

    Array(1, 2, 4, 8, 16).foreach { size =>
      val name = s"u256To${size}Byte!"
      testSimpleScript(main(name, size))
      val number = Val.U256(U256.unsafe(genNumber(size + 1)))
      failSimpleScript(main(name, size + 1), InvalidConversion(number, Val.ByteVec))
    }
    testSimpleScript(main("u256To32Byte!", 32))
    failSimpleScript(main("u256To32Byte!", 33), AssertionFailed)
  }

  it should "test u256 from bytes" in new ContractFixture {
    def main(func: String, size: Int): String = {
      val number = BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE)
      val u256   = U256.from(number).getOrElse(U256.MaxValue)
      val hex    = Hex.toHexString(IndexedSeq.fill(size)(0xff.toByte))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!($func(#$hex) == $u256)
         |}
         |""".stripMargin
    }

    Array(2, 4, 8, 16, 32).foreach { size =>
      val name = s"u256From${size}Byte!"
      testSimpleScript(main(name, size))
      failSimpleScript(main(name, size + 1), InvalidBytesSize)
      failSimpleScript(main(name, size - 1), InvalidBytesSize)
    }
    testSimpleScript(main("u256From1Byte!", 1))
    failSimpleScript(main("u256From1Byte!", 2), InvalidBytesSize)
  }

  it should "test bytevec slice" in new ContractFixture {
    val hex = "1b6dffea4ac54dbc4bbc65169dd054de826add0c62a85789662d477116304488"
    def main(start: Int, end: Int, slice: String): String = {
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(byteVecSlice!(#$hex, $start, $end) == #$slice)
         |}
         |""".stripMargin
    }

    testSimpleScript(main(4, 13, hex.slice(4 * 2, 13 * 2)))
    testSimpleScript(main(4, 4, ""))
    failSimpleScript(main(13, 4, "00"), InvalidBytesSliceArg)
    failSimpleScript(main(4, 33, "00"), InvalidBytesSliceArg)
  }

  it should "test bytevec to address" in new ContractFixture {
    val p2pkhAddress = Address.p2pkh(PublicKey.generate)
    val p2shAddress  = Address.Asset(LockupScript.p2sh(Hash.generate))
    val p2mpkhAddress = Address.Asset(
      LockupScript.p2mpkhUnsafe(
        AVector.fill(3)(PublicKey.generate),
        2
      )
    )
    val p2cAddress = Address.contract(Hash.generate)
    def main(address: Address): String = {
      val hex = Hex.toHexString(serialize(address.lockupScript))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(byteVecToAddress!(#$hex) == @${address.toBase58})
         |}
         |""".stripMargin
    }

    testSimpleScript(main(p2pkhAddress))
    testSimpleScript(main(p2shAddress))
    testSimpleScript(main(p2mpkhAddress))
    testSimpleScript(main(p2cAddress))
  }

  it should "create and use NFT contract" in new ContractFixture {
    val nftContract =
      s"""
         |// credits to @chloekek
         |TxContract Nft(author: Address, price: U256)
         |{
         |    @using(preapprovedAssets = true, assetsInContract = true)
         |    pub fn buy(buyer: Address) -> ()
         |    {
         |        transferAlph!(buyer, author, price)
         |        transferTokenFromSelf!(buyer, selfTokenId!(), 1)
         |        destroySelf!(author)
         |    }
         |}
         |""".stripMargin
    val tokenId =
      createContractAndCheckState(
        nftContract,
        2,
        2,
        initialState =
          AVector[Val](Val.Address(genesisAddress.lockupScript), Val.U256(U256.unsafe(1000000))),
        tokenAmount = Some(1024)
      ).key

    callTxScript(
      s"""
         |TxScript Main
         |{
         |  Nft(#${tokenId.toHexString}).buy{@$genesisAddress -> 1000000}(@${genesisAddress.toBase58})
         |}
         |
         |$nftContract
         |""".stripMargin
    )
  }

  it should "create and use Uniswap-like contract" in new ContractFixture {
    val tokenContract =
      s"""
         |TxContract Token(mut x: U256) {
         |  @using(assetsInContract = true)
         |  pub fn withdraw(address: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
         |  }
         |}
         |""".stripMargin
    val tokenContractKey =
      createContractAndCheckState(tokenContract, 2, 2, tokenAmount = Some(1024)).key
    val tokenId = tokenContractKey

    callTxScript(s"""
                    |TxScript Main {
                    |  let token = Token(#${tokenContractKey.toHexString})
                    |  token.withdraw(@${genesisAddress.toBase58}, 1024)
                    |}
                    |
                    |$tokenContract
                    |""".stripMargin)
    val swapContractKey = createContract(
      AMMContract.swapContract,
      AVector[Val](Val.ByteVec.from(tokenId), Val.U256(U256.Zero), Val.U256(U256.Zero)),
      tokenAmount = Some(1024)
    ).key

    def checkSwapBalance(alphReserve: U256, tokenReserve: U256) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val output     = worldState.getContractAsset(swapContractKey).rightValue
      output.amount is alphReserve
      output.tokens.toSeq.toMap.getOrElse(tokenId, U256.Zero) is tokenReserve
    }

    checkSwapBalance(minimalAlphInContract, 0)

    callTxScript(s"""
                    |TxScript Main {
                    |  let swap = Swap(#${swapContractKey.toHexString})
                    |  swap.addLiquidity{
                    |   @$genesisAddress -> 10, #${tokenId.toHexString}: 100
                    |  }(@${genesisAddress.toBase58}, 10, 100)
                    |}
                    |
                    |${AMMContract.swapContract}
                    |""".stripMargin)
    checkSwapBalance(minimalAlphInContract + 10, 100)

    callTxScript(s"""
                    |TxScript Main {
                    |  let swap = Swap(#${swapContractKey.toHexString})
                    |  swap.swapToken{@$genesisAddress -> 10}(@${genesisAddress.toBase58}, 10)
                    |}
                    |
                    |${AMMContract.swapContract}
                    |""".stripMargin)
    checkSwapBalance(minimalAlphInContract + 20, 50)

    callTxScript(
      s"""
         |TxScript Main {
         |  let swap = Swap(#${swapContractKey.toHexString})
         |  swap.swapAlph{@$genesisAddress -> #${tokenId.toHexString}: 50}(@$genesisAddress, 50)
         |}
         |
         |${AMMContract.swapContract}
         |""".stripMargin
    )
    checkSwapBalance(minimalAlphInContract + 10, 100)
  }

  it should "execute tx in random order" in new ContractFixture {
    val testContract =
      s"""
         |TxContract Foo(mut x: U256) {
         |  pub fn foo(y: U256) -> () {
         |    x = x * 10 + y
         |  }
         |}
         |""".stripMargin
    val contractKey = createContractAndCheckState(testContract, 2, 2).key

    val block = callTxScriptMulti(index => s"""
                                              |@using(preapprovedAssets = false)
                                              |TxScript Main {
                                              |  let foo = Foo(#${contractKey.toHexString})
                                              |  foo.foo($index)
                                              |}
                                              |
                                              |$testContract
                                              |""".stripMargin)

    val expected   = block.getNonCoinbaseExecutionOrder.fold(0L)(_ * 10 + _)
    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(contractKey).fold(throw _, identity)
    contractState.fields is AVector[Val](Val.U256(U256.unsafe(expected)))
  }

  it should "be able to call a contract multiple times in a block" in new ContractFixture {
    val testContract =
      s"""
         |TxContract Foo(mut x: U256) {
         |  @using(assetsInContract = true)
         |  pub fn foo(address: Address) -> () {
         |    x = x + 1
         |    transferAlphFromSelf!(address, ${ALPH.cent(1).v})
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(
        testContract,
        2,
        2,
        initialAttoAlphAmount = ALPH.alph(10)
      ).key

    def checkContract(alphReserve: U256, x: Int) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      val state      = worldState.getContractState(contractId).rightValue
      state.fields is AVector[Val](Val.U256(x))
      val output = worldState.getContractAsset(contractId).rightValue
      output.amount is alphReserve
    }

    checkContract(ALPH.alph(10), 0)

    val block0 = transfer(blockFlow, chainIndex, amount = ALPH.alph(10), numReceivers = 10)
    addAndCheck(blockFlow, block0)
    val newAddresses = block0.nonCoinbase.head.unsigned.fixedOutputs.init.map(_.lockupScript)

    def main(address: LockupScript.Asset) =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.foo(@${Address.Asset(address).toBase58})
         |}
         |
         |$testContract
         |""".stripMargin

    val validator = TxValidation.build
    val simpleTx  = transfer(blockFlow, chainIndex).nonCoinbase.head.toTemplate
    blockFlow.getMemPool(chainIndex).addNewTx(chainIndex, simpleTx, TimeStamp.now())
    newAddresses.foreachWithIndex { case (address, index) =>
      val gas    = if (index % 2 == 0) 20000 else 200000
      val script = Compiler.compileTxScript(main(address)).rightValue
      val tx = payableCallTxTemplate(
        blockFlow,
        chainIndex,
        address,
        script,
        initialGas = gas,
        validation = false
      )

      if (index % 2 == 0) {
        assume(tx.chainIndex == chainIndex)
        validator.validateMempoolTxTemplate(tx, blockFlow).leftValue isE TxScriptExeFailed(OutOfGas)
      } else {
        validator.validateMempoolTxTemplate(tx, blockFlow) isE ()
      }
      blockFlow
        .getMemPool(chainIndex)
        .addNewTx(chainIndex, tx, TimeStamp.now()) is AddedToSharedPool
    }

    val blockTemplate =
      blockFlow.prepareBlockFlowUnsafe(chainIndex, getGenesisLockupScript(chainIndex))
    blockTemplate.transactions.length is 12
    blockTemplate.transactions.filter(_.scriptExecutionOk == false).length is 5
    val block = mine(blockFlow, blockTemplate)
    addAndCheck0(blockFlow, block)
    checkContract(ALPH.cent(995), 5)
  }

  it should "test contract inheritance" in new ContractFixture {
    val contract: String =
      s"""
         |TxContract Child(mut x: U256) extends Parent0(x), Parent1(x) {
         |  pub fn foo() -> () {
         |    p0()
         |    p1()
         |    gp()
         |  }
         |}
         |
         |TxContract Grandparent(mut x: U256) {
         |  event GP(value: U256)
         |
         |  fn gp() -> () {
         |    x = x + 1
         |    emit GP(x)
         |  }
         |}
         |
         |TxContract Parent0(mut x: U256) extends Grandparent(x) {
         |  event Parent0(x: U256)
         |
         |  fn p0() -> () {
         |    emit Parent0(1)
         |    gp()
         |  }
         |}
         |
         |TxContract Parent1(mut x: U256) extends Grandparent(x) {
         |  event Parent1(x: U256)
         |
         |  fn p1() -> () {
         |    emit Parent1(2)
         |    gp()
         |  }
         |}
         |""".stripMargin

    val contractOutputRef = createContract(contract, AVector(Val.U256(0)))
    val contractId        = contractOutputRef.key.toHexString
    checkContractState(contractId, contractOutputRef, true)

    val script =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let child = Child(#$contractId)
         |  child.foo()
         |}
         |$contract
         |""".stripMargin

    val main  = Compiler.compileTxScript(script).rightValue
    val block = simpleScript(blockFlow, chainIndex, main)
    val txId  = block.nonCoinbase.head.id
    addAndCheck(blockFlow, block)

    val worldState    = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
    val contractState = worldState.getContractState(contractOutputRef.key).rightValue
    contractState.fields is AVector[Val](Val.U256(3))
    getLogStates(blockFlow, chainIndex.from, contractOutputRef.key, 0).value is
      LogStates(
        block.hash,
        contractOutputRef.key,
        AVector(
          LogState(txId, 0, AVector(Val.U256(1))),
          LogState(txId, 1, AVector(Val.U256(1))),
          LogState(txId, 2, AVector(Val.U256(2))),
          LogState(txId, 1, AVector(Val.U256(2))),
          LogState(txId, 1, AVector(Val.U256(3)))
        )
      )
  }

  trait EventFixture extends FlowFixture {
    def contractRaw: String
    def callingScriptRaw: String

    lazy val contract       = Compiler.compileContract(contractRaw).rightValue
    lazy val initialState   = AVector[Val](Val.U256.unsafe(10))
    lazy val chainIndex     = ChainIndex.unsafe(0, 0)
    lazy val fromLockup     = getGenesisLockupScript(chainIndex)
    lazy val genesisAddress = Address.Asset(fromLockup)
    lazy val contractCreationScript =
      contractCreation(contract, initialState, fromLockup, ALPH.alph(1))
    lazy val createContractBlock =
      payableCall(blockFlow, chainIndex, contractCreationScript)
    lazy val contractOutputRef =
      TxOutputRef.unsafe(createContractBlock.transactions.head, 0).asInstanceOf[ContractOutputRef]
    lazy val contractId = contractOutputRef.key

    addAndCheck(blockFlow, createContractBlock, 1)
    checkState(blockFlow, chainIndex, contractId, initialState, contractOutputRef)

    val callingScript = Compiler.compileTxScript(callingScriptRaw, 1).rightValue
    val callingBlock  = simpleScript(blockFlow, chainIndex, callingScript)
    addAndCheck(blockFlow, callingBlock, 2)
  }

  trait EventFixtureWithContract extends EventFixture {
    override def contractRaw: String =
      s"""
         |TxContract Foo(mut result: U256) {
         |
         |  event Adding(a: U256, b: U256)
         |  event Added()
         |
         |  pub fn add(a: U256) -> (U256) {
         |    emit Adding(a, result)
         |    result = result + a
         |    emit Added()
         |    return result
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    destroySelf!(targetAddress)
         |  }
         |}
         |""".stripMargin

    override def callingScriptRaw: String =
      s"""
         |$contractRaw
         |
         |@using(preapprovedAssets = false)
         |TxScript Bar {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.add(4)
         |
         |  return
         |}
         |""".stripMargin

    protected def verifyCallingEvents(
        logStates: LogStates,
        block: Block,
        result: Int,
        currentCount: Int
    ) = {
      logStates.blockHash is block.hash
      logStates.eventKey is contractId
      logStates.states.length is 2

      getCurrentCount(blockFlow, chainIndex.from, contractId).value is currentCount

      val addingLogState = logStates.states(0)
      addingLogState.txId is block.nonCoinbase.head.id
      addingLogState.index is 0.toByte
      addingLogState.fields.length is 2
      addingLogState.fields(0) is Val.U256(U256.unsafe(4))
      addingLogState.fields(1) is Val.U256(U256.unsafe(result))

      val addedLogState = logStates.states(1)
      addedLogState.txId is block.nonCoinbase.head.id
      addedLogState.index is 1.toByte
      addedLogState.fields.length is 0
    }
  }

  it should "emit events and write to the log storage" in new EventFixtureWithContract {
    {
      info("Events emitted from the contract exist in the block")

      val logStatesOpt = getLogStates(blockFlow, chainIndex.from, contractId, 0)
      val logStates    = logStatesOpt.value

      verifyCallingEvents(logStates, callingBlock, result = 10, currentCount = 1)
    }

    {
      info("Events emitted from the create contract block")

      val logStatesOpt =
        getLogStates(blockFlow, chainIndex.from, createContractEventId, 0)
      val logStates = logStatesOpt.value

      logStates.blockHash is createContractBlock.hash
      logStates.eventKey is createContractEventId
      logStates.states.length is 1

      getCurrentCount(blockFlow, chainIndex.from, createContractEventId).value is 1

      val createContractLogState = logStates.states(0)
      createContractLogState.txId is createContractBlock.nonCoinbase.head.id
      createContractLogState.index is -1.toByte
      createContractLogState.fields.length is 1
      createContractLogState.fields(0) is Val.Address(LockupScript.p2c(contractId))
    }

    {
      info("Events emitted from the destroy contract block")
      def destroyScriptRaw: String =
        s"""
           |$contractRaw
           |
           |TxScript Main {
           |  Foo(#${contractId.toHexString}).destroy(@${genesisAddress.toBase58})
           |}
           |""".stripMargin

      val destroyScript        = Compiler.compileTxScript(destroyScriptRaw, 1).rightValue
      val destroyContractBlock = payableCall(blockFlow, chainIndex, destroyScript)
      addAndCheck(blockFlow, destroyContractBlock, 3)

      val logStatesOpt =
        getLogStates(blockFlow, chainIndex.from, destroyContractEventId, 0)
      val logStates = logStatesOpt.value

      logStates.blockHash is destroyContractBlock.hash
      logStates.eventKey is destroyContractEventId
      logStates.states.length is 1

      getCurrentCount(blockFlow, chainIndex.from, destroyContractEventId).value is 1

      val destroyContractLogState = logStates.states(0)
      destroyContractLogState.txId is destroyContractBlock.nonCoinbase.head.id
      destroyContractLogState.index is -2.toByte
      destroyContractLogState.fields.length is 1
      destroyContractLogState.fields(0) is Val.Address(LockupScript.p2c(contractId))
    }

    {
      info("Events emitted from the contract with wrong counter")

      val logStatesOpt1 = getLogStates(blockFlow, chainIndex.from, contractId, 0)
      val logStates1    = logStatesOpt1.value
      val newCounter    = logStates1.states.length

      newCounter is 2

      AVector(1, 2, 100).foreach { count =>
        getLogStates(blockFlow, chainIndex.from, contractId, count) is None
      }
    }

    {
      info("Events emitted from a non-existent contract")

      val wrongContractId = Hash.generate
      val logStatesOpt    = getLogStates(blockFlow, chainIndex.from, wrongContractId, 0)
      logStatesOpt is None
    }
  }

  it should "not write to the log storage when logging is disabled" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig =
      LogConfig(enabled = false, indexByTxId = false, contractAddresses = None)

    getLogStates(blockFlow, chainIndex.from, contractId, 0) is None
  }

  it should "not write to the log storage when logging is enabled but contract is not whitelisted" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig(
      enabled = true,
      indexByTxId = true,
      contractAddresses = Some(AVector(Hash.generate, Hash.generate).map(Address.contract))
    )

    getLogStates(blockFlow, chainIndex.from, contractId, 0) is None
  }

  it should "write to the log storage without tx id indexing" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig(
      enabled = true,
      indexByTxId = false,
      contractAddresses = None
    )

    getLogStates(blockFlow, chainIndex.from, contractId, 0) isnot None
    val txId = callingBlock.nonCoinbase.head.id
    getLogStatesByTxId(blockFlow, chainIndex.from, txId) is None
  }

  it should "write to the log storage with tx id indexing" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig(
      enabled = true,
      indexByTxId = true,
      contractAddresses = None
    )

    getLogStates(blockFlow, chainIndex.from, contractId, 0) isnot None
    val txId = callingBlock.nonCoinbase.head.id
    getLogStatesByTxId(blockFlow, chainIndex.from, txId) isnot None
  }

  it should "write script events to log storage" in new EventFixture {
    override def contractRaw: String =
      s"""
         |TxContract Add(x: U256) {
         |  event Add1(a: U256, b: U256)
         |  event Add2(a: U256, b: U256)
         |
         |  pub fn add(a: U256, b: U256) -> U256 {
         |    emit Add1(a, b)
         |    emit Add2(a, b)
         |    return a + b
         |  }
         |}
         |""".stripMargin

    override def callingScriptRaw: String =
      s"""
         |$contractRaw
         |
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let contract = Add(#${contractId.toHexString})
         |  contract.add(1, 2)
         |}
         |""".stripMargin

    val contractLogStates = getLogStates(blockFlow, chainIndex.from, contractId, 0).value
    val txId              = callingBlock.nonCoinbase.head.id
    contractLogStates.blockHash is callingBlock.hash
    contractLogStates.eventKey is contractId
    contractLogStates.states.length is 2
    val fields = AVector[Val](Val.U256(1), Val.U256(2))
    contractLogStates.states(0) is LogState(txId, 0, fields)
    contractLogStates.states(1) is LogState(txId, 1, fields)

    val txIdLogStates = getLogStates(blockFlow, chainIndex.from, txId, 0).value
    txIdLogStates.blockHash is callingBlock.hash
    txIdLogStates.eventKey is txId
    txIdLogStates.states.length is 2

    val logStatesId = LogStatesId(contractId, 0)
    txIdLogStates
      .states(0) is LogState(txId, eventRefIndex, LogStateRef(logStatesId, 0).toFields)
    txIdLogStates
      .states(1) is LogState(txId, eventRefIndex, LogStateRef(logStatesId, 1).toFields)
  }

  it should "emit events with all supported field types" in new EventFixture {
    lazy val address = Address.Contract(LockupScript.P2C(Hash.generate))

    override def contractRaw: String =
      s"""
         |TxContract Foo(mut result: U256) {
         |
         |  event TestEvent1(a: U256, b: I256, c: Address, d: ByteVec)
         |  event TestEvent2(a: U256, b: I256, c: Address, d: Bool)
         |
         |  pub fn testEventTypes() -> (U256) {
         |    emit TestEvent1(4, -5i, @${address.toBase58}, byteVec!(@${address.toBase58}))
         |    let b = true
         |    emit TestEvent2(5, -4i, @${address.toBase58}, b)
         |    return result + 1
         |  }
         |}
         |""".stripMargin

    override def callingScriptRaw: String =
      s"""
         |$contractRaw
         |
         |@using(preapprovedAssets = false)
         |TxScript Bar {
         |  let foo = Foo(#${contractId.toHexString})
         |  foo.testEventTypes()
         |
         |  return
         |}
         |""".stripMargin

    val logStatesOpt = getLogStates(blockFlow, chainIndex.from, contractId, 0)
    val logStates    = logStatesOpt.value

    logStates.blockHash is callingBlock.hash
    logStates.eventKey is contractId
    logStates.states.length is 2

    getCurrentCount(blockFlow, chainIndex.from, contractId).value is 1

    val testEventLogState1 = logStates.states(0)
    testEventLogState1.txId is callingBlock.nonCoinbase.head.id
    testEventLogState1.index is 0.toByte
    testEventLogState1.fields.length is 4
    testEventLogState1.fields(0) is Val.U256(U256.unsafe(4))
    testEventLogState1.fields(1) is Val.I256(I256.unsafe(-5))
    testEventLogState1.fields(2) is Val.Address(address.lockupScript)
    testEventLogState1.fields(3) is Val.Address(address.lockupScript).toByteVec()

    val testEventLogState2 = logStates.states(1)
    testEventLogState1.txId is callingBlock.nonCoinbase.head.id
    testEventLogState2.index is 1.toByte
    testEventLogState2.fields.length is 4
    testEventLogState2.fields(0) is Val.U256(U256.unsafe(5))
    testEventLogState2.fields(1) is Val.I256(I256.unsafe(-4))
    testEventLogState2.fields(2) is Val.Address(address.lockupScript)
    testEventLogState2.fields(3) is Val.Bool(true)
  }

  it should "emit events for at most 8 fields" in new EventFixture {
    def contractRaw: String =
      s"""
         |TxContract Foo(tmp: U256) {
         |  event Foo(a1: U256, a2: U256, a3: U256, a4: U256, a5: U256, a6: U256, a7: U256, a8: U256)
         |
         |  pub fn foo() -> () {
         |    emit Foo(1, 2, 3, 4, 5, 6, 7, 8)
         |    return
         |  }
         |}
         |""".stripMargin

    def callingScriptRaw: String =
      s"""
         |$contractRaw
         |
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo()
         |}
         |""".stripMargin

    val logStatesOpt = getLogStates(blockFlow, chainIndex.from, contractId, 0)
    val logStates    = logStatesOpt.value

    logStates.blockHash is callingBlock.hash
    logStates.eventKey is contractId
    logStates.states.length is 1
    val logState = logStates.states.head
    logState.index is 0.toByte
    logState.fields.map(_.asInstanceOf[Val.U256].v.toIntUnsafe) is AVector.tabulate(8)(_ + 1)
  }

  it should "get all events emitted by a contract" in new EventFixtureWithContract {
    {
      info("All events emitted from the contract after the first method call")

      val (nextCount, allLogStates) = getEvents(blockFlow, contractId, 0)
      nextCount is 1
      allLogStates.length is 1
      val logStates = allLogStates.head

      verifyCallingEvents(logStates, callingBlock, result = 10, currentCount = 1)
    }

    val secondCallingBlock = simpleScript(blockFlow, chainIndex, callingScript)
    addAndCheck(blockFlow, secondCallingBlock, 3)

    {
      info("All events emitted from the contract after the second method call")

      val (nextCount1, _) = getEvents(blockFlow, contractId, 0, 1)
      nextCount1.value is 2
      val (nextCount2, _) = getEvents(blockFlow, contractId, 0, 2)
      nextCount2 is 2

      val (nextCount, allLogStates) = getEvents(blockFlow, contractId, 0)
      nextCount is 2
      allLogStates.length is 2
      val logStates1 = allLogStates.head
      val logStates2 = allLogStates.last

      verifyCallingEvents(logStates1, callingBlock, result = 10, currentCount = 2)
      verifyCallingEvents(logStates2, secondCallingBlock, result = 14, currentCount = 2)
    }

    {
      info("Part of the events emitted from the contract after the second method call")
      val (nextCount, allLogStates) = getEvents(blockFlow, contractId, 0, 2)
      nextCount is 2
      allLogStates.length is 2

      val logStates1 = allLogStates.head
      val logStates2 = allLogStates.last

      verifyCallingEvents(logStates1, callingBlock, result = 10, currentCount = 2)

      logStates2.blockHash is secondCallingBlock.hash
      logStates2.eventKey is contractId
      logStates2.states.length is 2

      val addingLogState = logStates2.states(0)
      addingLogState.txId is secondCallingBlock.nonCoinbase.head.id
      addingLogState.index is 0.toByte
      addingLogState.fields.length is 2
      addingLogState.fields(0) is Val.U256(U256.unsafe(4))
      addingLogState.fields(1) is Val.U256(U256.unsafe(14))
    }
  }

  it should "not compile when emitting events with array field types" in new FlowFixture {
    def contractRaw: String =
      s"""
         |TxContract Foo(mut result: U256) {
         |
         |  event TestEvent(f: [U256; 2])
         |
         |  pub fn testArrayEventType() -> (U256) {
         |    emit TestEvent([1, 2])
         |    return 0
         |  }
         |}
         |""".stripMargin
    Compiler.compileContract(contractRaw).leftValue is Compiler.Error(
      "Array type not supported for event TestEvent"
    )
  }

  private def getLogStates(
      blockFlow: BlockFlow,
      groupIndex: GroupIndex,
      contractId: ContractId,
      count: Int
  ): Option[LogStates] = {
    val logStatesId = LogStatesId(contractId, count)
    getLogStates(blockFlow, groupIndex, logStatesId)
  }

  private def getLogStatesByTxId(
      blockFlow: BlockFlow,
      groupIndex: GroupIndex,
      txId: Hash
  ): Option[LogStates] = {
    val logStatesId = LogStatesId(txId, 0)
    getLogStates(blockFlow, groupIndex, logStatesId)
  }

  private def getLogStates(
      blockFlow: BlockFlow,
      groupIndex: GroupIndex,
      logStatesId: LogStatesId
  ): Option[LogStates] = {
    (for {
      worldState   <- blockFlow.getBestPersistedWorldState(groupIndex)
      logStatesOpt <- worldState.logState.getOpt(logStatesId)
    } yield logStatesOpt).rightValue
  }

  it should "return contract id in contract creation" in new ContractFixture {
    val contract: String =
      s"""
         |TxContract Foo(mut subContractId: ByteVec) {
         |  event Create(subContractId: ByteVec)
         |
         |  @using(preapprovedAssets = true)
         |  pub fn foo() -> () {
         |    subContractId = copyCreateContract!{callerAddress!() -> $minimalAlphInContract}(selfContractId!(), #010300)
         |    emit Create(subContractId)
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(
        contract,
        2,
        2,
        AVector(Val.ByteVec(ByteString.empty)),
        initialAttoAlphAmount = minimalAlphInContract * 2
      ).key

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> 1 alph}()
         |}
         |
         |$contract
         |""".stripMargin
    val block = callTxScript(main)

    val logStatesOpt = getLogStates(blockFlow, chainIndex.from, contractId, 0)
    val logStates    = logStatesOpt.value
    logStates.blockHash is block.hash
    logStates.states.length is 1
    val subContractId = logStates.states(0).fields.head.asInstanceOf[Val.ByteVec].bytes

    val worldState = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
    worldState.getContractState(contractId).rightValue.fields is AVector[Val](
      Val.ByteVec(subContractId)
    )
  }

  trait SubContractFixture extends ContractFixture {
    val subContractRaw: String =
      s"""
         |TxContract SubContract() {
         |  pub fn call() -> () {
         |  }
         |}
         |""".stripMargin
    val subContractInitialState = Hex.toHexString(serialize(AVector.empty[Val]))

    // scalastyle:off method.length
    def verify(
        createContractStmt: String,
        subContractPath: String,
        numOfAssets: Int,
        numOfContracts: Int
    ) = {
      val contractRaw: String =
        s"""
           |TxContract Contract(mut subContractId: ByteVec) {
           |  @using(preapprovedAssets = true)
           |  pub fn createSubContract() -> () {
           |    subContractId = $createContractStmt
           |  }
           |
           |  pub fn callSubContract(path: ByteVec) -> () {
           |    let subContractIdCalculated = subContractId!(path)
           |    assert!(subContractIdCalculated == subContractId)
           |    SubContract(subContractIdCalculated).call()
           |  }
           |}
           |$subContractRaw
           |""".stripMargin

      val contractId =
        createContractAndCheckState(
          contractRaw,
          numOfAssets,
          numOfContracts,
          AVector(Val.ByteVec(ByteString.empty))
        ).key

      val createSubContractRaw: String =
        s"""
           |TxScript Main {
           |  Contract(#${contractId.toHexString}).createSubContract{callerAddress!() -> 1 alph}()
           |}
           |$contractRaw
           |""".stripMargin

      callTxScript(createSubContractRaw)

      val subContractId = Hash.doubleHash(serialize(subContractPath) ++ contractId.bytes)
      val worldState    = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      worldState.getContractState(contractId).rightValue.fields is AVector[Val](
        Val.ByteVec(subContractId.bytes)
      )

      intercept[AssertionError](callTxScript(createSubContractRaw)).getMessage.startsWith(
        s"Right(TxScriptExeFailed(ContractAlreadyExists(${subContractId.toHexString}))"
      )

      val subContractPathHex = Hex.toHexString(serialize(subContractPath))
      val callSubContractRaw: String =
        s"""
           |TxScript Main {
           |  Contract(#${contractId.toHexString}).callSubContract(#${subContractPathHex})
           |}
           |$contractRaw
           |""".stripMargin

      callTxScript(callSubContractRaw)
    }
    // scalastyle:on method.length
  }

  it should "check createSubContract and createSubContractWithToken" in new SubContractFixture {
    val subContract         = Compiler.compileContract(subContractRaw).rightValue
    val subContractByteCode = Hex.toHexString(serialize(subContract))

    val subContractPath1 = Hex.toHexString(serialize("nft-01"))
    verify(
      s"createSubContract!{callerAddress!() -> 1 alph}(#$subContractPath1, #$subContractByteCode, #$subContractInitialState)",
      subContractPath = "nft-01",
      numOfAssets = 2,
      numOfContracts = 2
    )

    val subContractPath2 = Hex.toHexString(serialize("nft-02"))
    verify(
      s"createSubContractWithToken!{callerAddress!() -> 1 alph}(#$subContractPath2, #$subContractByteCode, #$subContractInitialState, 10)",
      subContractPath = "nft-02",
      numOfAssets = 5,
      numOfContracts = 4
    )
  }

  it should "check copyCreateSubContract and copyCreateSubContractWithToken" in new SubContractFixture {
    val subContractId = createContractAndCheckState(subContractRaw, 2, 2, AVector.empty).key

    val subContractPath1 = Hex.toHexString(serialize("nft-01"))
    verify(
      s"copyCreateSubContract!{callerAddress!() -> 1 alph}(#$subContractPath1, #${subContractId.toHexString}, #$subContractInitialState)",
      subContractPath = "nft-01",
      numOfAssets = 3,
      numOfContracts = 3
    )

    val subContractPath2 = Hex.toHexString(serialize("nft-02"))
    verify(
      s"copyCreateSubContractWithToken!{callerAddress!() -> 1 alph}(#$subContractPath2, #${subContractId.toHexString}, #$subContractInitialState, 10)",
      subContractPath = "nft-02",
      numOfAssets = 6,
      numOfContracts = 5
    )
  }

  it should "not load contract just after creation" in new ContractFixture {
    val contract: String =
      s"""
         |TxContract Foo(mut subContractId: ByteVec) {
         |  @using(preapprovedAssets = true)
         |  pub fn foo() -> () {
         |    subContractId = copyCreateContract!{
         |      callerAddress!() -> ${ALPH.nanoAlph(1000).v}
         |    }(selfContractId!(), #010300)
         |    let subContract = Foo(subContractId)
         |    subContract.foo{callerAddress!() -> ${ALPH.nanoAlph(1000).v}}()
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(contract, 2, 2, AVector(Val.ByteVec(ByteString.empty))).key

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> 1 alph}()
         |}
         |
         |$contract
         |""".stripMargin
    val script = Compiler.compileTxScript(main).rightValue
    val errorMessage =
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
    errorMessage.contains(s"Right(TxScriptExeFailed(ContractLoadDisallowed") is true
  }

  it should "not call contract destruction from the same contract" in new ContractFixture {
    val foo: String =
      s"""
         |TxContract Foo() {
         |  pub fn foo(barId: ByteVec) -> () {
         |    let bar = Bar(barId)
         |    bar.bar(selfContractId!())
         |  }
         |  pub fn destroy() -> () {
         |    destroySelf!(callerAddress!())
         |  }
         |}
         |""".stripMargin
    val bar: String =
      s"""
         |TxContract Bar() {
         |  pub fn bar(fooId: ByteVec) -> () {
         |    let foo = Foo(fooId)
         |    foo.destroy()
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(s"$foo\n$bar", AVector.empty).key
    val barId = createContract(s"$bar\n$foo", AVector.empty).key

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo(#${barId.toHexString})
         |}
         |
         |$foo
         |""".stripMargin
    val script = Compiler.compileTxScript(main).rightValue
    val errorMessage =
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
    errorMessage is "Right(TxScriptExeFailed(ContractDestructionShouldNotBeCalledFromSelf))"
  }

  it should "encode values" in new ContractFixture {
    val foo: String =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |    let bytes = encodeToByteVec!(true, 1, false)
         |    assert!(bytes == #03000102010000)
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo, AVector.empty).key
    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo()
         |}
         |
         |$foo
         |""".stripMargin
    testSimpleScript(main)
  }

  it should "not pay to unloaded contract" in new ContractFixture {
    val foo: String =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val fooId      = createContract(foo, AVector.empty).key
    val fooAddress = Address.contract(fooId).toBase58

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo()
         |  transferAlph!(callerAddress!(), @${fooAddress}, 1 alph)
         |}
         |
         |$foo
         |""".stripMargin
    failCallTxScript(main, PayToContractAddressNotInCallerTrace)
  }

  it should "work with interface" in new ContractFixture {
    val interface =
      s"""
         |Interface I {
         |  pub fn f1() -> U256
         |  pub fn f2() -> U256
         |  pub fn f3() -> ByteVec
         |}
         |""".stripMargin

    val contract =
      s"""
         |TxContract Foo() implements I {
         |  pub fn f3() -> ByteVec {
         |    return #00
         |  }
         |
         |  pub fn f2() -> U256 {
         |    return 2
         |  }
         |
         |  pub fn f1() -> U256 {
         |    return 1
         |  }
         |}
         |
         |$interface
         |""".stripMargin

    val contractId = createContract(contract, AVector.empty).key

    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let impl = I(#${contractId.toHexString})
         |  assert!(impl.f1() == 1)
         |}
         |
         |$interface
         |""".stripMargin

    callTxScript(main)
  }

  it should "inherit interface events" in new ContractFixture {
    val foo: String =
      s"""
         |Interface Foo {
         |  event Foo(x: U256)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    val bar: String =
      s"""
         |TxContract Bar() implements Foo {
         |  event Bar(x: U256)
         |  pub fn foo() -> () {
         |    emit Foo(1)
         |    emit Bar(2)
         |  }
         |}
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector.empty).key

    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let foo = Foo(#${barId.toHexString})
         |  foo.foo()
         |}
         |$foo
         |""".stripMargin
    val block = callTxScript(main)

    val logStatesOpt = getLogStates(blockFlow, chainIndex.from, barId, 0)
    val logStates    = logStatesOpt.value
    logStates.blockHash is block.hash
    logStates.states.length is 2
    logStates.states(0).fields.head.asInstanceOf[Val.U256].v.toIntUnsafe is 1
    logStates.states(1).fields.head.asInstanceOf[Val.U256].v.toIntUnsafe is 2
  }

  it should "not be able to transfer assets right after contract is created" in new ContractFixture {
    val foo: String =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |  }
         |}
         |""".stripMargin

    val fooContract     = Compiler.compileContract(foo).rightValue
    val fooByteCode     = Hex.toHexString(serialize(fooContract))
    val fooInitialState = Hex.toHexString(serialize(AVector.empty[Val]))

    def createFooContract(transferAlph: Boolean): String = {
      val maybeTransfer = if (transferAlph) {
        s"transferAlphFromSelf!(contractAddress, ${minimalAlphInContract.v})"
      } else {
        ""
      }

      val bar: String =
        s"""
           |TxContract Bar() {
           |  @using(preapprovedAssets = true, assetsInContract = $transferAlph)
           |  pub fn bar() -> () {
           |    let contractId = createContract!{@$genesisAddress -> $minimalAlphInContract}(#$fooByteCode, #$fooInitialState)
           |    let contractAddress = contractIdToAddress!(contractId)
           |
           |    $maybeTransfer
           |  }
           |}
           |""".stripMargin

      val barContractId =
        createContract(bar, AVector.empty, initialAttoAlphAmount = ALPH.alph(2)).key

      s"""
         |TxScript Main {
         |  let bar = Bar(#${barContractId.toHexString})
         |  bar.bar{@$genesisAddress -> $minimalAlphInContract}()
         |}
         |
         |$bar
         |""".stripMargin
    }

    callTxScript(createFooContract(false))
    failCallTxScript(createFooContract(true), PayToContractAddressNotInCallerTrace)
  }

  it should "not transfer assets to arbitrary contract" in new ContractFixture {
    val randomContract = Address.contract(ContractId.random).toBase58

    {
      info("Transfer to random contract address in TxScript")
      val script =
        s"""
           |TxScript Main {
           |  let caller = callerAddress!()
           |  transferAlph!(caller, @${randomContract}, 0.01 alph)
           |}
           |""".stripMargin
      failCallTxScript(script, PayToContractAddressNotInCallerTrace)
    }

    {
      info("Transfer to random contract address in TxContract")

      val foo: String =
        s"""
           |TxContract Foo() {
           |  @using(assetsInContract = true)
           |  pub fn foo() -> () {
           |    transferAlphFromSelf!(@${randomContract}, 0.01 alph)
           |  }
           |}
           |""".stripMargin
      val fooId      = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(2)).key
      val fooAddress = Address.contract(fooId)

      val script =
        s"""
           |TxScript Main {
           |  let caller = callerAddress!()
           |  transferAlph!(caller, @${fooAddress}, 0.01 alph)
           |  let foo = Foo(#${fooId.toHexString})
           |  foo.foo()
           |}
           |
           |$foo
           |""".stripMargin
      failCallTxScript(script, PayToContractAddressNotInCallerTrace)
    }

    {
      info("Transfer to one of the caller addresses in TxContract")
      val foo: String =
        s"""
           |TxContract Foo() {
           |  @using(assetsInContract = true)
           |  pub fn foo(to: Address) -> () {
           |    transferAlphFromSelf!(to, 0.01 alph)
           |  }
           |}
           |""".stripMargin
      val fooId = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(2)).key

      val bar: String =
        s"""
           |TxContract Bar(index: U256, nextBarId: ByteVec) {
           |  @using(assetsInContract = true)
           |  pub fn bar(to: Address) -> () {
           |    if (index == 0) {
           |      let foo = Foo(#${fooId.toHexString})
           |      foo.foo(to)
           |    } else {
           |      let bar = Bar(nextBarId)
           |      transferAlphFromSelf!(selfAddress!(), 0) // dirty hack
           |      bar.bar(to)
           |    }
           |  }
           |}
           |$foo
           |""".stripMargin

      var lastBarId: ContractId = fooId
      (0 until 5).foreach { index =>
        val initialFields =
          AVector[Val](Val.U256(U256.unsafe(index)), Val.ByteVec(lastBarId.bytes))
        val barId = createContract(bar, initialFields, initialAttoAlphAmount = ALPH.alph(2)).key
        lastBarId = barId
      }

      val script =
        s"""
           |TxScript Main {
           |  let bar = Bar(#${lastBarId.toHexString})
           |  bar.bar(@${Address.contract(lastBarId)})
           |}
           |
           |$bar
           |""".stripMargin
      callTxScript(script)
    }
  }

  private def getEvents(
      blockFlow: BlockFlow,
      contractId: ContractId,
      start: Int,
      end: Int = Int.MaxValue
  ): (Int, AVector[LogStates]) = {
    blockFlow.getEvents(contractId, start, end).rightValue
  }

  private def getCurrentCount(
      blockFlow: BlockFlow,
      groupIndex: GroupIndex,
      contractId: ContractId
  ): Option[Int] = {
    (for {
      worldState <- blockFlow.getBestPersistedWorldState(groupIndex)
      countOpt   <- worldState.logCounterState.getOpt(contractId)
    } yield countOpt).rightValue
  }
}
// scalastyle:on file.size.limit no.equal regex
