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
import org.alephium.flow.mempool.MemPool.AddedToMemPool
import org.alephium.flow.validation.{TxScriptExeFailed, TxValidation}
import org.alephium.protocol.{ALPH, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler
import org.alephium.serde.{serialize, Serde}
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
         |Contract Foo(mut x: U256) {
         |  @using(updateFields = true)
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
    lazy val contractId0 = ContractId.from(block0.transactions.head.id, 0, chainIndex.from)

    lazy val input1 =
      s"""
         |Contract Foo(mut x: U256) {
         |  @using(updateFields = true)
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
         |  let foo = Foo(#${contractId0.toHexString})
         |  foo.add(4)
         |  return
         |}
         |""".stripMargin
  }

  it should "not call external private function" in new CallFixture {
    val access: String = ""

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractId0, initialState, contractOutputRef0)

    val script1 = Compiler.compileTxScript(input1, 1).rightValue
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script1)).getMessage is
      s"Right(TxScriptExeFailed($ExternalPrivateMethodCall))"
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "pub"

    addAndCheck(blockFlow, block0, 1)
    checkState(blockFlow, chainIndex, contractId0, initialState, contractOutputRef0)

    val script1   = Compiler.compileTxScript(input1, 1).rightValue
    val newState1 = AVector[Val](Val.U256(U256.unsafe(10)))
    val block1    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block1, 2)
    checkState(blockFlow, chainIndex, contractId0, newState1, contractOutputRef0, numAssets = 4)

    val newState2 = AVector[Val](Val.U256(U256.unsafe(20)))
    val block2    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block2, 3)
    checkState(blockFlow, chainIndex, contractId0, newState2, contractOutputRef0, numAssets = 6)
  }

  trait ContractFixture extends FlowFixture {
    lazy val chainIndex     = ChainIndex.unsafe(0, 0)
    lazy val genesisLockup  = getGenesisLockupScript(chainIndex)
    lazy val genesisAddress = Address.Asset(genesisLockup)

    def createContractAndCheckState(
        input: String,
        numAssets: Int,
        numContracts: Int,
        initialState: AVector[Val] = AVector[Val](Val.U256(U256.Zero)),
        tokenIssuanceInfo: Option[TokenIssuance.Info] = None,
        initialAttoAlphAmount: U256 = minimalAlphInContract
    ): (ContractId, ContractOutputRef) = {
      val (contractId, contractOutputRef) =
        createContract(input, initialState, tokenIssuanceInfo, initialAttoAlphAmount)

      checkState(
        blockFlow,
        chainIndex,
        contractId,
        initialState,
        contractOutputRef,
        numAssets,
        numContracts
      )

      (contractId, contractOutputRef)
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

    def callTxScriptMulti(input: Int => String, func: StatefulScript => StatefulScript): Block = {
      val block0 = transfer(blockFlow, chainIndex, numReceivers = 10)
      addAndCheck(blockFlow, block0)
      val newAddresses = block0.nonCoinbase.head.unsigned.fixedOutputs.init.map(_.lockupScript)
      val scripts = AVector.tabulate(newAddresses.length) { index =>
        val script = Compiler.compileTxScript(input(index)).fold(throw _, identity)
        func(script)
      }
      val block1 = simpleScriptMulti(blockFlow, chainIndex, newAddresses, scripts)
      addAndCheck(blockFlow, block1)
      block1
    }

    def testSimpleScript(main: String, gas: Int = 100000) = {
      val script = Compiler.compileTxScript(main).rightValue
      val block  = simpleScript(blockFlow, chainIndex, script, gas)
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
      val contractKey = ContractId.from(Hex.from(contractId).get).get
      worldState.contractState.exists(contractKey) isE existed
      worldState.outputState.exists(contractAssetRef) isE existed
    }

    def getContractAsset(contractId: ContractId, chainIndex: ChainIndex): ContractOutput = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      worldState.getContractAsset(contractId).rightValue
    }
  }

  it should "disallow loading upgraded contract in current tx" in new ContractFixture {
    val fooV1Code =
      s"""
         |Contract FooV1() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin
    val fooV1 = Compiler.compileContract(fooV1Code).rightValue

    val fooV0Code =
      s"""
         |Contract FooV0() {
         |  pub fn upgrade() -> () {
         |    migrate!(#${Hex.toHexString(serialize(fooV1))})
         |  }
         |}
         |""".stripMargin
    val fooContractId = createContract(fooV0Code, AVector.empty)._1.toHexString

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

  it should "create contract and optionally transfer token" in new ContractFixture {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn main() -> () {
         |  }
         |}
         |""".stripMargin

    {
      info("create contract with token")
      val contractId = createContract(
        code,
        initialState = AVector.empty,
        Some(TokenIssuance.Info(Val.U256.unsafe(10), None))
      )._1
      val tokenId = TokenId.from(contractId)

      val genesisTokenAmount = getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId)
      genesisTokenAmount is 0

      val contractAsset = getContractAsset(contractId, chainIndex)
      contractAsset.tokens is AVector((tokenId, U256.unsafe(10)))
    }

    {
      info("create contract and transfer token to asset address")
      val contractId = createContract(
        code,
        initialState = AVector.empty,
        Some(TokenIssuance.Info(Val.U256.unsafe(10), Some(genesisLockup)))
      )._1
      val tokenId = TokenId.from(contractId)

      val genesisTokenAmount = getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId)
      genesisTokenAmount is 10

      val contractAsset = getContractAsset(contractId, chainIndex)
      contractAsset.tokens.length is 0
    }

    {
      info("create contract and transfer token to contract address")
      val contract         = Compiler.compileContract(code).rightValue
      val contractByteCode = Hex.toHexString(serialize(contract))
      val contractAddress  = Address.contract(ContractId.random).toBase58
      val encodedState     = Hex.toHexString(serialize[AVector[Val]](AVector.empty))

      val script: String =
        s"""
           |TxScript Main {
           |  createContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractByteCode, #$encodedState, 1, @$contractAddress)
           |}
           |""".stripMargin

      failCallTxScript(script, InvalidAssetAddress)
    }
  }

  it should "transfer ALPH by token id" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn foo(sender: Address) -> () {
         |    let senderAlph = tokenRemaining!(sender, ALPH)
         |    assert!(tokenRemaining!(sender, ALPH) == senderAlph, 0)
         |    let contractAlph = tokenRemaining!(selfAddress!(), ALPH)
         |    assert!(tokenRemaining!(selfAddress!(), ALPH) == contractAlph, 0)
         |
         |    transferTokenToSelf!(sender, ALPH, 1 alph)
         |    assert!(tokenRemaining!(sender, ALPH) == senderAlph - 1 alph, 0)
         |    transferTokenFromSelf!(sender, ALPH, 1 alph)
         |    assert!(tokenRemaining!(selfAddress!(), ALPH) == contractAlph - 1 alph, 0)
         |    transferToken!(sender, selfAddress!(), ALPH, 1 alph)
         |    assert!(tokenRemaining!(sender, ALPH) == senderAlph - 2 alph, 0)
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo, AVector.empty)._1

    val script =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo{@$genesisAddress -> ALPH: 3 alph}(@$genesisAddress)
         |}
         |$foo
         |""".stripMargin

    callTxScript(script)

    val worldState    = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
    val contractAsset = worldState.getContractAsset(fooId).rightValue
    contractAsset.amount is ALPH.alph(2)
  }

  it should "create contract and transfer tokens from the contract" in new ContractFixture {
    val code =
      s"""
         |Contract ShinyToken() {
         |  @using(assetsInContract = true)
         |  pub fn transfer(to: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(to, selfContractId!(), amount)
         |    transferTokenFromSelf!(to, ALPH, dustAmount!())
         |  }
         |}
         |""".stripMargin

    def script(shinyTokenId: String, to: String, amount: U256): String =
      s"""
         |TxScript Transfer() {
         |  ShinyToken(#$shinyTokenId).transfer(@$to, ${amount.v})
         |}
         |
         |$code
         |""".stripMargin

    info("create contract with token")
    val contractId = createContract(
      code,
      initialState = AVector.empty,
      Some(TokenIssuance.Info(Val.U256.unsafe(1000), None)),
      ALPH.alph(10000)
    )._1
    val tokenId = TokenId.from(contractId)

    getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId) is 0

    val contractAsset = getContractAsset(contractId, chainIndex)
    contractAsset.tokens is AVector((tokenId, U256.unsafe(1000)))
    contractAsset.amount is ALPH.alph(10000)

    info("transfer token to genesisAddress")
    callTxScript(script(tokenId.toHexString, genesisAddress.toBase58, 10))
    getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId) is 10

    info("transfer token from contract to address in group 0")
    val (privateKey0, publicKey0) = GroupIndex.unsafe(0).generateKey
    val address0                  = Address.p2pkh(publicKey0)
    callTxScript(script(tokenId.toHexString, address0.toBase58, 100))
    getTokenBalance(blockFlow, address0.lockupScript, tokenId) is 100
    getAlphBalance(blockFlow, address0.lockupScript) is dustUtxoAmount

    info("fail to transfer token from contract to address in group 1")
    val (_, publicKey1) = GroupIndex.unsafe(1).generateKey
    val address1        = Address.p2pkh(publicKey1)
    address1.groupIndex.value is 1
    intercept[AssertionError](callTxScript(script(tokenId.toHexString, address1.toBase58, 50)))
      .getMessage() is "Right(InvalidOutputGroupIndex)"

    info("transfer some ALPH to adress in group 0")
    val genesisPrivateKey = genesisKeys(chainIndex.from.value)._1
    val block = transfer(
      blockFlow,
      genesisPrivateKey,
      address0.lockupScript,
      AVector.empty[(TokenId, U256)],
      ALPH.oneAlph
    )
    addAndCheck(blockFlow, block)
    getAlphBalance(blockFlow, address0.lockupScript) is (dustUtxoAmount + ALPH.oneAlph)

    info("transfer token from address in group 0 to address in group 1")
    val tokens: AVector[(TokenId, U256)] = AVector(tokenId -> 10)
    transfer(
      blockFlow,
      privateKey0,
      address1.lockupScript,
      tokens,
      minimalAttoAlphAmountPerTxOutput(1)
    )
  }

  it should "burn token" in new ContractFixture {
    val contract =
      s"""
         |Contract Foo() {
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
    val contractId =
      createContract(contract, AVector.empty, Some(TokenIssuance.Info(ALPH.alph(5))))._1
    val tokenId = TokenId.from(contractId)

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
    val contractAsset0 = getContractAsset(contractId, chainIndex)
    contractAsset0.lockupScript is LockupScript.p2c(contractId)
    contractAsset0.tokens is AVector(tokenId -> ALPH.alph(3))
    val tokenAmount0 = getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId)
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
    val contractAsset1 = getContractAsset(contractId, chainIndex)
    contractAsset1.lockupScript is LockupScript.p2c(contractId)
    contractAsset1.tokens is AVector(tokenId -> ALPH.alph(2))
    val tokenAmount1 = getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId)
    tokenAmount1 is ALPH.alph(1)
  }

  it should "lock assets" in new ContractFixture {
    val token =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn mint() -> () {
         |    transferTokenFromSelf!(@$genesisAddress, selfTokenId!(), 10 alph)
         |  }
         |}
         |""".stripMargin

    import org.alephium.protocol.model.TokenId.tokenIdOrder
    val _tokenId0 =
      TokenId.from(
        createContract(token, AVector.empty, Some(TokenIssuance.Info(ALPH.alph(100))))._1
      )
    val _tokenId1 =
      TokenId.from(
        createContract(token, AVector.empty, Some(TokenIssuance.Info(ALPH.alph(100))))._1
      )
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
         |  lockApprovedAssets!{ @$genesisAddress -> ALPH: 0.01 alph }(@$genesisAddress, timestamp0)
         |
         |  lockApprovedAssets!{
         |    @$genesisAddress -> ALPH: 0.02 alph, #$tokenId0Hex: 0.03 alph
         |  }(@$genesisAddress, timestamp1)
         |
         |  lockApprovedAssets!{
         |    @$genesisAddress -> ALPH: 0.04 alph, #$tokenId0Hex: 0.05 alph, #$tokenId1Hex: 0.06 alph
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
        |Contract Foo() {
        |  @using(assetsInContract = true)
        |  pub fn foo(address: Address) -> () {
        |    transferTokenFromSelf!(address, ALPH, tokenRemaining!(selfAddress!(), ALPH))
        |  }
        |}
        |""".stripMargin

    val contractId = createContractAndCheckState(input, 2, 2, AVector.empty)._1

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
         |Contract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
         |    return x
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |
         |Contract Bar() {
         |  pub fn bar(foo: ByteVec) -> (U256) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |""".stripMargin
    val (contractKey0, contractOutputRef0) = createContractAndCheckState(input0, 2, 2)

    val input1 =
      s"""
         |Contract Bar() {
         |  pub fn bar(foo: ByteVec) -> (U256) {
         |    return Foo(foo).get() + 100
         |  }
         |}
         |
         |Contract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
         |    return x
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn foo(foo: ByteVec, bar: ByteVec) -> () {
         |    x = x + 10
         |    x = Bar(bar).bar(foo)
         |    return
         |  }
         |}
         |
         |""".stripMargin
    val contractId1 = createContractAndCheckState(input1, 3, 3, initialState = AVector.empty)._1

    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let foo = Foo(#${contractKey0.toHexString})
         |  foo.foo(#${contractKey0.toHexString}, #${contractId1.toHexString})
         |}
         |
         |Contract Foo(mut x: U256) {
         |  pub fn get() -> (U256) {
         |    return x
         |  }
         |
         |  @using(updateFields = true)
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
      numAssets = 5, // 3 + 1 coinbase output + 1 transfer in simple script tx
      numContracts = 3
    )
  }

  it should "issue new token" in new ContractFixture {
    val input =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val contractKey = createContractAndCheckState(
      input,
      2,
      2,
      tokenIssuanceInfo = Some(TokenIssuance.Info(10000000)),
      initialState = AVector.empty
    )._1
    val tokenId = TokenId.from(contractKey)

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().rightValue.length is 2
    worldState.getContractOutputs(ByteString.empty, Int.MaxValue).rightValue.foreach {
      case (ref, output) =>
        if (ref != ContractOutputRef.forSMT) {
          output.tokens.head is (tokenId -> U256.unsafe(10000000))
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
         |  assert!(r == $out, 0)
         |
         |  test()
         |
         |  fn test() -> () {
         |    assert!((33 + 2 - 3) * 5 / 7 % 11 == 0, 0)
         |
         |    let x = 0
         |    let y = 1
         |    assert!(x << 1 == 0, 0)
         |    assert!(x >> 1 == 0, 0)
         |    assert!(y << 1 == 2, 0)
         |    assert!(y >> 1 == 0, 0)
         |    assert!(y << 255 != 0, 0)
         |    assert!(y << 256 == 0, 0)
         |    assert!(x & x == 0, 0)
         |    assert!(x & y == 0, 0)
         |    assert!(y & y == 1, 0)
         |    assert!(x | x == 0, 0)
         |    assert!(x | y == 1, 0)
         |    assert!(y | y == 1, 0)
         |    assert!(x ^ x == 0, 0)
         |    assert!(x ^ y == 1, 0)
         |    assert!(y ^ y == 0, 0)
         |
         |    assert!((x < y) == true, 0)
         |    assert!((x <= y) == true, 0)
         |    assert!((x < x) == false, 0)
         |    assert!((x <= x) == true, 0)
         |    assert!((x > y) == false, 0)
         |    assert!((x >= y) == false, 0)
         |    assert!((x > x) == false, 0)
         |    assert!((x >= x) == true, 0)
         |
         |    assert!((true && true) == true, 0)
         |    assert!((true && false) == false, 0)
         |    assert!((false && false) == false, 0)
         |    assert!((true || true) == true, 0)
         |    assert!((true || false) == true, 0)
         |    assert!((false || false) == false, 0)
         |
         |    assert!(!true == false, 0)
         |    assert!(!false == true, 0)
         |  }
         |}
         |""".stripMargin
    // scalastyle:on no.equal

    testSimpleScript(expect(1))
    failSimpleScript(expect(2), AssertionFailedWithErrorCode(None, 0))
  }
  // scalastyle:on method.length

  // scalastyle:off no.equal
  it should "test ByteVec instructions" in new ContractFixture {
    def encode[T: Serde](t: T): String = Hex.toHexString(serialize(t))

    val i256    = UnsecureRandom.nextI256()
    val u256    = UnsecureRandom.nextU256()
    val address = Address.from(LockupScript.p2c(ContractId.random))
    val bytes0  = Hex.toHexString(Hash.random.bytes)
    val bytes1  = Hex.toHexString(Hash.random.bytes)

    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript ByteVecTest {
         |  assert!(toByteVec!(true) == #${encode(true)}, 0)
         |  assert!(toByteVec!(false) == #${encode(false)}, 0)
         |  assert!(toByteVec!(${i256}i) == #${encode(i256)}, 0)
         |  assert!(toByteVec!(${u256}) == #${encode(u256)}, 0)
         |  assert!(toByteVec!(@${address.toBase58}) == #${encode(address.lockupScript)}, 0)
         |  assert!(# ++ #$bytes0 == #$bytes0, 0)
         |  assert!(#$bytes0 ++ # == #$bytes0, 0)
         |  assert!((#${bytes0} ++ #${bytes1}) == #${bytes0 ++ bytes1}, 0)
         |  assert!(size!(toByteVec!(true)) == 1, 0)
         |  assert!(size!(toByteVec!(false)) == 1, 0)
         |  assert!(size!(toByteVec!(@${address.toBase58})) == 33, 0)
         |  assert!(size!(#${bytes0} ++ #${bytes1}) == 64, 0)
         |  assert!(zeros!(2) == #0000, 0)
         |  assert!(nullContractAddress!() == @${Address.contract(ContractId.zero)}, 0)
         |  assert!(nullContractAddress!() == @tgx7VNFoP9DJiFMFgXXtafQZkUvyEdDHT9ryamHJYrjq, 0)
         |  assert!(blockHash!() != #${Hash.zero.toHexString}, 0)
         |  assert!(ALPH == zeros!(32), 0)
         |  assert!(ALPH == #0000000000000000000000000000000000000000000000000000000000000000, 0)
         |}
         |""".stripMargin

    testSimpleScript(main)
  }

  it should "test conversion functions" in new ContractFixture {
    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript ByteVecTest {
         |  assert!(toI256!(1) == 1i, 0)
         |  assert!(toU256!(1i) == 1, 0)
         |  assert!(toByteVec!(true) == #01, 0)
         |  assert!(toByteVec!(false) == #00, 0)
         |}
         |""".stripMargin

    testSimpleScript(main)
  }

  it should "test CopyCreateContractWithToken instruction" in new ContractFixture {
    val fooContract =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |  }
         |}
         |""".stripMargin

    val contractId = createContract(fooContract, AVector.empty)._1.toHexString

    val encodedState = Hex.toHexString(serialize[AVector[Val]](AVector.empty))
    val tokenAmount  = ALPH.oneNanoAlph

    {
      info("copy create contract with token")
      val script: String =
        s"""
           |TxScript Main {
           |  copyCreateContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractId, #$encodedState, ${tokenAmount.v})
           |}
           |""".stripMargin

      val block          = callTxScript(script)
      val transaction    = block.nonCoinbase.head
      val contractOutput = transaction.generatedOutputs(0).asInstanceOf[ContractOutput]
      val tokenId        = TokenId.from(contractOutput.lockupScript.contractId)
      contractOutput.tokens is AVector((tokenId, tokenAmount))
    }

    {
      info("copy create contract and transfer token to asset address")
      val script: String =
        s"""
           |TxScript Main {
           |  copyCreateContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractId, #$encodedState, ${tokenAmount.v}, @${genesisAddress.toBase58})
           |}
           |""".stripMargin

      val block          = callTxScript(script)
      val transaction    = block.nonCoinbase.head
      val contractOutput = transaction.generatedOutputs(0).asInstanceOf[ContractOutput]
      val tokenId        = TokenId.unsafe(contractOutput.lockupScript.contractId.value)
      contractOutput.tokens.length is 0
      getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId) is tokenAmount
    }

    {
      info("copy create contract and transfer token to contract address")
      val contractAddress = Address.contract(ContractId.random)
      val script: String =
        s"""
           |TxScript Main {
           |  copyCreateContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractId, #$encodedState, ${tokenAmount.v}, @${contractAddress.toBase58})
           |}
           |""".stripMargin

      failCallTxScript(script, InvalidAssetAddress)
    }
  }

  // scalastyle:off no.equal
  it should "test contract instructions" in new ContractFixture {
    def createContract(input: String): (String, String, String, String) = {
      val contractId    = createContract(input, initialState = AVector.empty)._1
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
         |Contract Foo() {
         |  pub fn foo(fooId: ByteVec, fooHash: ByteVec, fooCodeHash: ByteVec, barId: ByteVec, barHash: ByteVec, barCodeHash: ByteVec, barAddress: Address) -> () {
         |    assert!(selfContractId!() == fooId, 0)
         |    assert!(contractInitialStateHash!(fooId) == fooHash, 0)
         |    assert!(contractInitialStateHash!(barId) == barHash, 0)
         |    assert!(contractCodeHash!(fooId) == fooCodeHash, 0)
         |    assert!(contractCodeHash!(barId) == barCodeHash, 0)
         |    assert!(callerContractId!() == barId, 0)
         |    assert!(callerAddress!() == barAddress, 0)
         |    assert!(callerInitialStateHash!() == barHash, 0)
         |    assert!(callerCodeHash!() == barCodeHash, 0)
         |    assert!(isCalledFromTxScript!() == false, 0)
         |    assert!(isAssetAddress!(barAddress) == false, 0)
         |    assert!(isContractAddress!(barAddress) == true, 0)
         |  }
         |}
         |""".stripMargin
    val (fooId, _, fooHash, fooCodeHash) = createContract(foo)

    val bar =
      s"""
         |Contract Bar() {
         |  @using(preapprovedAssets = true)
         |  pub fn bar(fooId: ByteVec, fooHash: ByteVec, fooCodeHash: ByteVec, barId: ByteVec, barHash: ByteVec, barCodeHash: ByteVec, barAddress: Address) -> () {
         |    assert!(selfContractId!() == barId, 0)
         |    assert!(selfAddress!() == barAddress, 0)
         |    assert!(contractInitialStateHash!(fooId) == fooHash, 0)
         |    assert!(contractInitialStateHash!(barId) == barHash, 0)
         |    Foo(#$fooId).foo(fooId, fooHash, fooCodeHash, barId, barHash, barCodeHash, barAddress)
         |    assert!(isCalledFromTxScript!() == true, 0)
         |    assert!(isAssetAddress!(@$genesisAddress) == true, 0)
         |    assert!(isContractAddress!(@$genesisAddress) == false, 0)
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val (barId, barAddress, barHash, barCodeHash) = createContract(bar)

    def main(state: String) =
      s"""
         |TxScript Main {
         |  Bar(#$barId).bar{ @$genesisAddress -> ALPH: 1 alph }(#$fooId, #$fooHash, #$fooCodeHash, #$barId, #$barHash, #$barCodeHash, @$barAddress)
         |  copyCreateContract!{ @$genesisAddress -> ALPH: 1 alph }(#$fooId, #$state)
         |}
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
      val contractId       = ContractId.generate
      val address: Address = Address.contract(contractId)
      val addressHex       = Hex.toHexString(serialize(address.lockupScript))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let address = contractIdToAddress!(#${contractId.toHexString})
         |  assert!(byteVecToAddress!(#$addressHex) == address, 0)
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

  it should "test contract exists" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    assert!(contractExists!(selfContractId!()), 0)
         |    assert!(!contractExists!(#${Hash.generate.toHexString}), 0)
         |  }
         |}
         |""".stripMargin
    val contractId = createContract(foo, AVector.empty)._1.toHexString

    val script =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  Foo(#$contractId).foo()
         |}
         |$foo
         |""".stripMargin
    callTxScript(script)
  }

  trait DestroyFixture extends ContractFixture {
    def prepareContract(
        contract: String,
        initialState: AVector[Val] = AVector.empty,
        initialAttoAlphAmount: U256 = minimalAlphInContract
    ): (String, ContractOutputRef) = {
      val contractId =
        createContract(contract, initialState, initialAttoAlphAmount = initialAttoAlphAmount)._1
      val worldState       = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractAssetRef = worldState.getContractState(contractId).rightValue.contractOutputRef
      contractId.toHexString -> contractAssetRef
    }
  }

  trait VerifyRecipientAddress { _: DestroyFixture =>
    val foo =
      s"""
         |Contract Foo(mut x: U256) {
         |  @using(assetsInContract = true, updateFields = true)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    x = x + 1
         |    destroySelf!(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |""".stripMargin
    val (fooId, fooAssetRef) = prepareContract(foo, AVector(Val.U256(0)))
    checkContractState(fooId, fooAssetRef, true)

    lazy val fooCaller =
      s"""
         |Contract FooCaller() {
         |  pub fn destroyFooWithAddress(targetAddress: Address) -> () {
         |    Foo(#$fooId).destroy(targetAddress)
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn destroyFoo() -> () {
         |    Foo(#$fooId).destroy(selfAddress!())
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    lazy val (fooCallerId, fooCallerAssetRef) = prepareContract(fooCaller, AVector.empty)

    lazy val callerOfFooCaller =
      s"""
         |Contract CallerOfFooCaller() {
         |  @using(assetsInContract = true)
         |  pub fn destroyFoo() -> () {
         |    FooCaller(#$fooCallerId).destroyFooWithAddress(selfAddress!())
         |  }
         |}
         |
         |$fooCaller
         |""".stripMargin
    lazy val (callerOfFooCallerId, callerOfFooCallerAssetRef) =
      prepareContract(callerOfFooCaller, AVector.empty)
  }

  it should "destroy contract directly" in new DestroyFixture with VerifyRecipientAddress {
    def destroy(targetAddress: String) =
      s"""
         |TxScript Main {
         |  Foo(#$fooId).destroy(@$targetAddress)
         |}
         |
         |$foo
         |""".stripMargin

    {
      info("Destroy a contract and transfer value to non-calling contract address")
      val address = Address.Contract(LockupScript.P2C(ContractId.generate))
      val script  = Compiler.compileTxScript(destroy(address.toBase58)).rightValue
      fail(blockFlow, chainIndex, script, PayToContractAddressNotInCallerTrace)
      checkContractState(fooId, fooAssetRef, true)
    }

    {
      info("Destroy a contract and and transfer value to itself")
      val fooAddress = Address.contract(ContractId.unsafe(Hash.unsafe(Hex.unsafe(fooId))))
      val script     = Compiler.compileTxScript(destroy(fooAddress.toBase58)).rightValue
      fail(blockFlow, chainIndex, script, ContractAssetAlreadyFlushed)
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
      callTxScript(destroy(genesisAddress.toBase58))
      checkContractState(fooId, fooAssetRef, false)
    }
  }

  it should "destroy contract and transfer fund to caller" in new DestroyFixture
    with VerifyRecipientAddress {
    def destroy() =
      s"""
         |TxScript Main {
         |  FooCaller(#$fooCallerId).destroyFoo()
         |}
         |
         |$fooCaller
         |""".stripMargin

    val fooCallerContractId  = ContractId.unsafe(Hash.unsafe(Hex.unsafe(fooCallerId)))
    val fooCallerAssetBefore = getContractAsset(fooCallerContractId, chainIndex)
    fooCallerAssetBefore.amount is ALPH.oneAlph

    callTxScript(destroy())
    checkContractState(fooId, fooAssetRef, false)

    val fooCallerAssetAfter = getContractAsset(fooCallerContractId, chainIndex)
    fooCallerAssetAfter.amount is ALPH.alph(2)
  }

  it should "destroy contract and transfer fund to caller's caller" in new DestroyFixture
    with VerifyRecipientAddress {
    def destroy() =
      s"""
         |TxScript Main {
         |  CallerOfFooCaller(#$callerOfFooCallerId).destroyFoo()
         |}
         |
         |$callerOfFooCaller
         |""".stripMargin

    callTxScript(destroy())
    checkContractState(fooId, fooAssetRef, false)
  }

  it should "not destroy a contract after approving assets" in new DestroyFixture {
    def buildFoo(useAssetsInContract: Boolean) =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = $useAssetsInContract)
         |  pub fn destroy(targetAddress: Address) -> () {
         |    approveToken!(selfAddress!(), ALPH, 2 alph)
         |    destroySelf!(targetAddress)
         |  }
         |}
         |""".stripMargin
    def main(fooId: ContractId, foo: String) =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).destroy(@$genesisAddress)
         |}
         |$foo
         |""".stripMargin
    def test(useAssetsInContract: Boolean, error: ExeFailure) = {
      val foo   = buildFoo(useAssetsInContract)
      val fooId = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(10))._1
      failCallTxScript(main(fooId, foo), error)
    }

    test(useAssetsInContract = true, ContractAssetAlreadyFlushed)
    test(useAssetsInContract = false, NoBalanceAvailable)
  }

  it should "migrate contract" in new DestroyFixture {
    val fooV1 =
      s"""
         |Contract Foo(x: Bool) {
         |  @using(updateFields = true)
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
         |    assert!(x == expected, 0)
         |  }
         |}
         |""".stripMargin
    val (fooId, _) = prepareContract(fooV1, AVector[Val](Val.True))
    val fooV2 =
      s"""
         |Contract Foo(x: Bool) {
         |  @using(updateFields = true)
         |  pub fn foo(code: ByteVec, changeState: Bool) -> () {
         |    if (changeState) {
         |      migrateWithFields!(code, #010000)
         |    } else {
         |      migrate!(code)
         |    }
         |  }
         |
         |  pub fn checkX(expected: Bool) -> () {
         |    assert!(x == expected, 0)
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
      val contractKey = ContractId.from(Hex.from(fooId).get).get
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
      val contractKey = ContractId.from(Hex.from(fooId).get).get
      val obj         = worldState.getContractObj(contractKey).rightValue
      obj.contractId is contractKey
      obj.code is fooV2Code.toHalfDecoded()
      obj.initialFields is AVector[Val](Val.False)
    }
  }

  it should "call contract destroy function from another contract" in new DestroyFixture {
    val foo =
      s"""
         |Contract Foo() {
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
         |Contract Bar() {
         |  pub fn bar(targetAddress: Address) -> () {
         |    Foo(#$fooId).destroy(targetAddress) // in practice, the contract should check the caller before destruction
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector.empty)._1.toHexString

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
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo(targetAddress: Address) -> () {
         |    approveToken!(selfAddress!(), ALPH, tokenRemaining!(selfAddress!(), ALPH))
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
    failCallTxScript(main, ContractAssetAlreadyInUsing)
  }

  it should "fetch block env" in new ContractFixture {
    def main(latestHeader: BlockHeader) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(networkId!() == #02, 0)
         |  assert!(blockTimeStamp!() >= ${latestHeader.timestamp.millis}, 0)
         |  assert!(blockTarget!() == ${latestHeader.target.value}, 0)
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
    val zeroId    = Hash.zero
    val gasAmount = GasBox.unsafe(150000)
    def main(index: Int) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript TxEnv {
         |  assert!(txId!() != #${zeroId.toHexString}, 0)
         |  assert!(txInputAddress!($index) == @${genesisAddress.toBase58}, 0)
         |  assert!(txInputsSize!() == 1, 0)
         |  assert!(txGasPrice!() == ${defaultGasPrice.value}, 0)
         |  assert!(txGasAmount!() == ${gasAmount.value}, 0)
         |  assert!(txGasFee!() == ${defaultGasPrice * gasAmount}, 0)
         |}
         |""".stripMargin
    testSimpleScript(main(0), gasAmount.value)
    failSimpleScript(main(1), InvalidTxInputIndex)
  }

  it should "test dust amount" in new ContractFixture {
    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(dustAmount!() == 0.001 alph, 0)
         |  assert!(dustAmount!() == $dustUtxoAmount, 0)
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
         |  assert!(blake2b!(#$input) == #8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b37a, 0)
         |  assert!(blake2b!(#$input) != #8947bee8a082f643a8ceab187d866e8ec0be8c2d7d84ffa8922a6db77644b370, 0)
         |  assert!(keccak256!(#$input) == #2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E3C, 0)
         |  assert!(keccak256!(#$input) != #2744686CE50A2A5AE2A94D18A3A51149E2F21F7EEB4178DE954A2DFCADC21E30, 0)
         |  assert!(sha256!(#$input) == #6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A9A, 0)
         |  assert!(sha256!(#$input) != #6D1103674F29502C873DE14E48E9E432EC6CF6DB76272C7B0DAD186BB92C9A90, 0)
         |  assert!(sha3!(#$input) == #f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed743, 0)
         |  assert!(sha3!(#$input) != #f5ad69e6b85ae4a51264df200c2bd19fbc337e4160c77dfaa1ea98cbae8ed740, 0)
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
         |  assert!(address == #${Hex.toHexString(address)}, 0)
         |}
         |""".stripMargin
    testSimpleScript(main(messageHash.bytes, signature, address))
    failSimpleScript(main(signature, messageHash.bytes, address), FailedInRecoverEthAddress)
    failSimpleScript(
      main(messageHash.bytes, signature, Hash.random.bytes.take(20)),
      AssertionFailedWithErrorCode(None, 0)
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
         |  assert!($func($number) == #$hex, 0)
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
    failSimpleScript(main("u256To32Byte!", 33), AssertionFailedWithErrorCode(None, 0))
  }

  it should "test u256 from bytes" in new ContractFixture {
    def main(func: String, size: Int): String = {
      val number = BigInteger.ONE.shiftLeft(size * 8).subtract(BigInteger.ONE)
      val u256   = U256.from(number).getOrElse(U256.MaxValue)
      val hex    = Hex.toHexString(IndexedSeq.fill(size)(0xff.toByte))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!($func(#$hex) == $u256, 0)
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
         |  assert!(byteVecSlice!(#$hex, $start, $end) == #$slice, 0)
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
    val p2cAddress = Address.contract(ContractId.generate)
    def main(address: Address): String = {
      val hex = Hex.toHexString(serialize(address.lockupScript))
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(byteVecToAddress!(#$hex) == @${address.toBase58}, 0)
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
         |Contract Nft(author: Address, price: U256)
         |{
         |    @using(preapprovedAssets = true, assetsInContract = true)
         |    pub fn buy(buyer: Address) -> ()
         |    {
         |        transferToken!(buyer, author, ALPH, price)
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
        tokenIssuanceInfo = Some(TokenIssuance.Info(1024))
      )._1

    callTxScript(
      s"""
         |TxScript Main
         |{
         |  Nft(#${tokenId.toHexString}).buy{@$genesisAddress -> ALPH: 1000000}(@${genesisAddress.toBase58})
         |}
         |
         |$nftContract
         |""".stripMargin
    )
  }

  it should "create and use Uniswap-like contract" in new ContractFixture {
    val tokenContract =
      s"""
         |Contract Token() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw(address: Address, amount: U256) -> () {
         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(
        tokenContract,
        2,
        2,
        tokenIssuanceInfo = Some(TokenIssuance.Info(1024)),
        initialState = AVector.empty
      )._1
    val tokenId = TokenId.from(contractId)

    callTxScript(s"""
                    |TxScript Main {
                    |  let token = Token(#${tokenId.toHexString})
                    |  token.withdraw(@${genesisAddress.toBase58}, 1024)
                    |}
                    |
                    |$tokenContract
                    |""".stripMargin)
    val swapContractId = createContract(
      AMMContract.swapContract,
      AVector[Val](Val.ByteVec.from(tokenId.value), Val.U256(U256.Zero), Val.U256(U256.Zero)),
      tokenIssuanceInfo = Some(TokenIssuance.Info(1024))
    )._1

    def checkSwapBalance(
        alphReserve: U256,
        tokenReserve: U256,
        numAssetOutput: Int,
        numContractOutput: Int
    ) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val output     = worldState.getContractAsset(swapContractId).rightValue
      output.amount is alphReserve
      output.tokens.toSeq.toMap.getOrElse(tokenId, U256.Zero) is tokenReserve

      worldState
        .getAssetOutputs(ByteString.empty, Int.MaxValue, (_, _) => true)
        .rightValue
        .length is numAssetOutput
      worldState
        .getContractOutputs(ByteString.empty, Int.MaxValue)
        .rightValue
        .length is numContractOutput
    }

    checkSwapBalance(minimalAlphInContract, 0, 4, 3)

    callTxScript(s"""
                    |TxScript Main {
                    |  let swap = Swap(#${swapContractId.toHexString})
                    |  swap.addLiquidity{
                    |   @$genesisAddress -> ALPH: 10, #${tokenId.toHexString}: 100
                    |  }(@${genesisAddress.toBase58}, 10, 100)
                    |}
                    |
                    |${AMMContract.swapContract}
                    |""".stripMargin)
    checkSwapBalance(minimalAlphInContract + 10, 100, 5 /* 1 more coinbase output */, 3)

    callTxScript(s"""
                    |TxScript Main {
                    |  let swap = Swap(#${swapContractId.toHexString})
                    |  swap.swapToken{@$genesisAddress -> ALPH: 10}(@${genesisAddress.toBase58}, 10)
                    |}
                    |
                    |${AMMContract.swapContract}
                    |""".stripMargin)
    checkSwapBalance(minimalAlphInContract + 20, 50, 6 /* 1 more coinbase output */, 3)

    callTxScript(
      s"""
         |TxScript Main {
         |  let swap = Swap(#${swapContractId.toHexString})
         |  swap.swapAlph{@$genesisAddress -> #${tokenId.toHexString}: 50}(@$genesisAddress, 50)
         |}
         |
         |${AMMContract.swapContract}
         |""".stripMargin
    )
    checkSwapBalance(minimalAlphInContract + 10, 100, 7 /* 1 more coinbase output */, 3)
  }

  trait TxExecutionOrderFixture extends ContractFixture {
    val testContract =
      s"""
         |Contract Foo(mut x: U256) {
         |  @using(updateFields = true)
         |  pub fn foo(y: U256) -> () {
         |    x = x * 10 + y
         |  }
         |}
         |""".stripMargin

    def callScript(contractId: ContractId, func: StatefulScript => StatefulScript) = {
      callTxScriptMulti(
        index => s"""
                    |@using(preapprovedAssets = false)
                    |TxScript Main {
                    |  let foo = Foo(#${contractId.toHexString})
                    |  foo.foo($index)
                    |}
                    |
                    |$testContract
                    |""".stripMargin,
        func
      )
    }

    def checkState(expected: Long, contractId: ContractId) = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val contractState = worldState.getContractState(contractId).fold(throw _, identity)
      contractState.fields is AVector[Val](Val.U256(U256.unsafe(expected)))
    }
  }

  it should "execute tx in random order" in new TxExecutionOrderFixture {
    override val configValues = Map(
      ("alephium.network.leman-hard-fork-timestamp", TimeStamp.now().plusHoursUnsafe(1).millis)
    )
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Mainnet

    def contractCreationPreLeman(
        code: StatefulContract,
        initialState: AVector[Val],
        lockupScript: LockupScript.Asset,
        attoAlphAmount: U256
    ): StatefulScript = {
      val codeRaw  = serialize(code)
      val stateRaw = serialize(initialState)
      val instrs = AVector[Instr[StatefulContext]](
        AddressConst(Val.Address(lockupScript)),
        U256Const(Val.U256(attoAlphAmount)),
        ApproveAlph,
        BytesConst(Val.ByteVec(codeRaw)),
        BytesConst(Val.ByteVec(stateRaw)),
        CreateContract
      )
      val method = Method[StatefulContext](
        isPublic = true,
        usePreapprovedAssets = true,
        useContractAssets = true,
        argsLength = 0,
        localsLength = 0,
        returnLength = 0,
        instrs = instrs
      )
      StatefulScript.unsafe(AVector(method))
    }

    def createContractPreLeman() = {
      val contract      = Compiler.compileContract(testContract).rightValue
      val genesisLockup = getGenesisLockupScript(chainIndex)
      val initialState  = AVector[Val](Val.U256(0))
      val txScript =
        contractCreationPreLeman(contract, initialState, genesisLockup, minimalAlphInContract)
      val block = payableCall(blockFlow, chainIndex, txScript)
      addAndCheck(blockFlow, block)

      val contractOutputRef =
        TxOutputRef.unsafe(block.transactions.head, 0).asInstanceOf[ContractOutputRef]
      val contractId = ContractId.deprecatedFrom(block.transactions.head.id, 0)
      val estimated  = contractId.inaccurateFirstOutputRef()
      estimated.hint is contractOutputRef.hint
      estimated.key.value.bytes.init is contractOutputRef.key.value.bytes.init

      checkState(blockFlow, chainIndex, contractId, initialState, contractOutputRef, 2, 2)
      contractId
    }

    val contractId = createContractPreLeman()
    val block = callScript(
      contractId,
      script => {
        script.methods.length is 1
        val method = script.methods(0)
        method.instrs.length is 7
        method.instrs(3) is U256Const1 // arg length
        method.instrs(4) is U256Const0 // return length
        val newMethod = method.copy(instrs = method.instrs.slice(0, 3) ++ method.instrs.slice(5, 7))
        StatefulScript.unsafe(AVector(newMethod))
      }
    )
    val expected = block.getNonCoinbaseExecutionOrder.fold(0L)(_ * 10 + _)
    checkState(expected, contractId)
  }

  it should "execute tx in sequential order" in new TxExecutionOrderFixture {
    val contractId = createContractAndCheckState(testContract, 2, 2)._1
    val block      = callScript(contractId, identity)
    networkConfig.getHardFork(block.timestamp) is HardFork.Leman

    val expected = (0L until block.nonCoinbaseLength.toLong).fold(0L)(_ * 10 + _)
    checkState(expected, contractId)
  }

  it should "be able to call a contract multiple times in a block" in new ContractFixture {
    val testContract =
      s"""
         |Contract Foo(mut x: U256) {
         |  @using(assetsInContract = true, updateFields = true)
         |  pub fn foo(address: Address) -> () {
         |    x = x + 1
         |    transferTokenFromSelf!(address, ALPH, ${ALPH.cent(1).v})
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(
        testContract,
        2,
        2,
        initialAttoAlphAmount = ALPH.alph(10)
      )._1

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
    blockFlow.getGrandPool().add(chainIndex, simpleTx, TimeStamp.now())
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
        .getGrandPool()
        .add(chainIndex, tx, TimeStamp.now()) is AddedToMemPool
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
    {
      info("Inherit Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p0()
           |    p1()
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Grandparent(mut x: U256) {
           |  event GP(value: U256)
           |
           |  @using(updateFields = true)
           |  fn gp() -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |}
           |
           |Abstract Contract Parent0(mut x: U256) extends Grandparent(x) {
           |  event Parent0(x: U256)
           |
           |  fn p0() -> () {
           |    emit Parent0(1)
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Parent1(mut x: U256) extends Grandparent(x) {
           |  event Parent1(x: U256)
           |
           |  fn p1() -> () {
           |    emit Parent1(2)
           |    gp()
           |  }
           |}
           |""".stripMargin

      success(contract)
    }

    {
      info("Inherit single Abstract Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p0()
           |    p1()
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Grandparent(mut x: U256) {
           |  event GP(value: U256)
           |
           |  @using(updateFields = true)
           |  fn gp() -> ()
           |}
           |
           |Abstract Contract Parent0(mut x: U256) extends Grandparent(x) {
           |  event Parent0(x: U256)
           |
           |  fn p0() -> () {
           |    emit Parent0(1)
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Parent1(mut x: U256) extends Grandparent(x) {
           |  event Parent1(x: U256)
           |
           |  @using(updateFields = true)
           |  fn gp() -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |
           |  fn p1() -> () {
           |    emit Parent1(2)
           |    gp()
           |  }
           |}
           |""".stripMargin

      success(contract)
    }

    {
      info("Inherit multiple Abstract Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  @using(updateFields = true)
           |  fn gp() -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |
           |  pub fn foo() -> () {
           |    p0()
           |    p1()
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Grandparent(mut x: U256) {
           |  event GP(value: U256)
           |
           |  @using(updateFields = true)
           |  fn gp() -> ()
           |}
           |
           |Abstract Contract Parent0(mut x: U256) extends Grandparent(x) {
           |  event Parent0(x: U256)
           |
           |  fn p0() -> () {
           |    emit Parent0(1)
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Parent1(mut x: U256) extends Grandparent(x) {
           |  event Parent1(x: U256)
           |
           |  fn p1() -> () {
           |    emit Parent1(2)
           |    gp()
           |  }
           |}
           |""".stripMargin

      success(contract)
    }

    {
      info("Inherit both Abstract Contract and Interface")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p0()
           |    p1()
           |    gp()
           |    ggp()
           |  }
           |}
           |
           |Interface GreatGrandparent {
           |  @using(checkExternalCaller = false)
           |  fn ggp() -> ()
           |}
           |
           |Abstract Contract Grandparent(mut x: U256) implements GreatGrandparent {
           |  event GP(value: U256)
           |
           |  @using(checkExternalCaller = false)
           |  fn ggp() -> () {}
           |
           |  @using(updateFields = true)
           |  fn gp() -> ()
           |}
           |
           |Abstract Contract Parent0(mut x: U256) extends Grandparent(x) {
           |  event Parent0(x: U256)
           |
           |  fn p0() -> () {
           |    emit Parent0(1)
           |    gp()
           |  }
           |}
           |
           |Abstract Contract Parent1(mut x: U256) extends Grandparent(x) {
           |  event Parent1(x: U256)
           |
           |  @using(updateFields = true)
           |  fn gp() -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |
           |  fn p1() -> () {
           |    emit Parent1(2)
           |    gp()
           |  }
           |}
           |""".stripMargin

      success(contract)
    }

    {
      info("miss abstract keyword for Abstract Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  @using(updateFields = true)
           |  fn gp() -> () {
           |    x = x + 1
           |    emit GP(x)
           |  }
           |}
           |
           |Abstract Contract Grandparent(mut x: U256) {
           |  event GP(value: U256)
           |
           |  @using(updateFields = true)
           |  fn gp() -> ()
           |}
           |
           |Contract Parent0(mut x: U256) extends Grandparent(x) {
           |  event Parent0(x: U256)
           |
           |  pub fn foo() -> () {
           |    p0()
           |    p1()
           |    gp()
           |  }
           |
           |  fn p0() -> () {
           |    emit Parent0(1)
           |    gp()
           |  }
           |}
           |
           |Contract Parent1(mut x: U256) extends Grandparent(x) {
           |  event Parent1(x: U256)
           |
           |  fn p1() -> () {
           |    emit Parent1(2)
           |    gp()
           |  }
           |}
           |""".stripMargin

      fail(contract, "Contract Parent0 has unimplemented methods: gp")
    }

    {
      info("conflicting abstract methods between Abstract Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p()
           |  }
           |
           |  fn p() -> () {
           |    emit Parent0(0)
           |    emit Parent1(1)
           |  }
           |}
           |
           |Abstract Contract Parent0(mut x: U256) {
           |  event Parent0(x: U256)
           |  fn p() -> ()
           |}
           |
           |Abstract Contract Parent1(mut x: U256) {
           |  event Parent1(x: U256)
           |  fn p() -> ()
           |}
           |""".stripMargin

      fail(contract, "These abstract functions are defined multiple times: p")
    }

    {
      info("conflicting abstract methods between Abstract Contract and Interface")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent1(x) implements Parent0 {
           |  pub fn foo() -> () {
           |    p()
           |  }
           |
           |  fn p() -> () {
           |    emit Parent0(0)
           |    emit Parent1(1)
           |  }
           |}
           |
           |Interface Parent0 {
           |  event Parent0(x: U256)
           |  fn p() -> ()
           |}
           |
           |Abstract Contract Parent1(mut x: U256) {
           |  event Parent1(x: U256)
           |  fn p() -> ()
           |}
           |""".stripMargin

      fail(contract, "These abstract functions are defined multiple times: p")
    }

    {
      info("conflicting method implementation between Abstract Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p()
           |  }
           |  fn p0() -> () {
           |    return
           |  }
           |}
           |
           |Abstract Contract Parent0(mut x: U256) {
           |  event Parent0(x: U256)
           |  fn p() -> () {
           |    emit Parent0(0)
           |  }
           |}
           |
           |Abstract Contract Parent1(mut x: U256) {
           |  event Parent1(x: U256)
           |  fn p() -> () {
           |    emit Parent1(1)
           |  }
           |  fn p0() -> ()
           |}
           |""".stripMargin

      fail(contract, "These functions are implemented multiple times: p")
    }

    {
      info("conflicting method implementation between Contract and Abstract Contract")

      val contract: String =
        s"""
           |Contract Child(mut x: U256) extends Parent0(x), Parent1(x) {
           |  pub fn foo() -> () {
           |    p()
           |  }
           |  fn p() -> () {
           |    emit Parent0(0)
           |  }
           |  fn p0() -> () {
           |    return
           |  }
           |}
           |
           |Abstract Contract Parent0(mut x: U256) {
           |  event Parent0(x: U256)
           |  fn p() -> ()
           |}
           |
           |Abstract Contract Parent1(mut x: U256) {
           |  event Parent1(x: U256)
           |  fn p() -> () {
           |    emit Parent1(1)
           |  }
           |  fn p0() -> ()
           |}
           |""".stripMargin

      fail(contract, "These functions are implemented multiple times: p")
    }

    def success(contract: String) = {
      val (contractId, contractOutputRef) = createContract(contract, AVector(Val.U256(0)))
      val contractIdHex                   = contractId.toHexString
      checkContractState(contractIdHex, contractOutputRef, true)

      val script =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  let child = Child(#$contractIdHex)
           |  child.foo()
           |}
           |$contract
           |""".stripMargin

      val main  = Compiler.compileTxScript(script).rightValue
      val block = simpleScript(blockFlow, chainIndex, main)
      val txId  = block.nonCoinbase.head.id
      addAndCheck(blockFlow, block)

      val worldState    = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractState = worldState.getContractState(contractId).rightValue
      contractState.fields is AVector[Val](Val.U256(3))
      getLogStates(blockFlow, contractId, 0).value is
        LogStates(
          block.hash,
          contractId,
          AVector(
            LogState(txId, 0, AVector(Val.U256(1))),
            LogState(txId, 1, AVector(Val.U256(1))),
            LogState(txId, 2, AVector(Val.U256(2))),
            LogState(txId, 1, AVector(Val.U256(2))),
            LogState(txId, 1, AVector(Val.U256(3)))
          )
        )
    }

    def fail(contract: String, errorMessage: String) = {
      Compiler.compileContract(contract).leftValue.message is errorMessage
    }
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
    lazy val contractId =
      ContractId.from(createContractBlock.transactions.head.id, 0, chainIndex.from)

    addAndCheck(blockFlow, createContractBlock, 1)
    checkState(blockFlow, chainIndex, contractId, initialState, contractOutputRef)

    val callingScript = Compiler.compileTxScript(callingScriptRaw, 1).rightValue
    val callingBlock  = simpleScript(blockFlow, chainIndex, callingScript)
    addAndCheck(blockFlow, callingBlock, 2)
  }

  trait EventFixtureWithContract extends EventFixture {
    override def contractRaw: String =
      s"""
         |Contract Foo(mut result: U256) {
         |
         |  event Adding(a: U256, b: U256)
         |  event Added()
         |
         |  @using(updateFields = true)
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
      logStates.contractId is contractId
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

      val logStatesOpt = getLogStates(blockFlow, contractId, 0)
      val logStates    = logStatesOpt.value

      verifyCallingEvents(logStates, callingBlock, result = 10, currentCount = 1)
    }

    {
      info("Events emitted from the create contract block")

      val logStatesOpt = getLogStates(blockFlow, createContractEventId, 0)
      val logStates    = logStatesOpt.value

      logStates.blockHash is createContractBlock.hash
      logStates.contractId is createContractEventId
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

      val logStatesOpt = getLogStates(blockFlow, destroyContractEventId, 0)
      val logStates    = logStatesOpt.value

      logStates.blockHash is destroyContractBlock.hash
      logStates.contractId is destroyContractEventId
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

      val logStatesOpt1 = getLogStates(blockFlow, contractId, 0)
      val logStates1    = logStatesOpt1.value
      val newCounter    = logStates1.states.length

      newCounter is 2

      AVector(1, 2, 100).foreach { count =>
        getLogStates(blockFlow, contractId, count) is None
      }
    }

    {
      info("Events emitted from a non-existent contract")

      val wrongContractId = ContractId.generate
      val logStatesOpt    = getLogStates(blockFlow, wrongContractId, 0)
      logStatesOpt is None
    }
  }

  it should "not write to the log storage when logging is disabled" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig.disabled()

    getLogStates(blockFlow, contractId, 0) is None
  }

  it should "not write to the log storage when logging is enabled but contract is not whitelisted" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig(
      enabled = true,
      indexByTxId = true,
      indexByBlockHash = true,
      contractAddresses =
        Some(AVector(ContractId.generate, ContractId.generate).map(Address.contract))
    )

    getLogStates(blockFlow, contractId, 0) is None
  }

  it should "write to the log storage without tx id indexing" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig(
      enabled = true,
      indexByTxId = false,
      indexByBlockHash = false,
      contractAddresses = None
    )

    getLogStates(blockFlow, contractId, 0) isnot None
    val txId = callingBlock.nonCoinbase.head.id
    getLogStatesByTxId(blockFlow, txId).isEmpty is true
  }

  it should "write to the log storage with tx id indexing" in new EventFixtureWithContract {
    implicit override lazy val logConfig: LogConfig = LogConfig(
      enabled = true,
      indexByTxId = true,
      indexByBlockHash = false,
      contractAddresses = None
    )

    getLogStates(blockFlow, contractId, 0) isnot None
    val txId = callingBlock.nonCoinbase.head.id
    getLogStatesByTxId(blockFlow, txId).isEmpty is false
  }

  it should "write script events to log storage" in new EventFixture {
    override def contractRaw: String =
      s"""
         |Contract Add(x: U256) {
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

    val contractLogStates = getLogStates(blockFlow, contractId, 0).value
    val txId              = callingBlock.nonCoinbase.head.id
    contractLogStates.blockHash is callingBlock.hash
    contractLogStates.contractId is contractId
    contractLogStates.states.length is 2
    val fields = AVector[Val](Val.U256(1), Val.U256(2))
    contractLogStates.states(0) is LogState(txId, 0, fields)
    contractLogStates.states(1) is LogState(txId, 1, fields)

    val txIdLogRefs = getLogStatesByTxId(blockFlow, txId)
    txIdLogRefs.length is 2

    val logStatesId = LogStatesId(contractId, 0)
    txIdLogRefs(0) is LogStateRef(logStatesId, 0)
    txIdLogRefs(1) is LogStateRef(logStatesId, 1)
  }

  it should "emit events with all supported field types" in new EventFixture {
    lazy val address = Address.Contract(LockupScript.P2C(ContractId.generate))

    override def contractRaw: String =
      s"""
         |Contract Foo(mut result: U256) {
         |
         |  event TestEvent1(a: U256, b: I256, c: Address, d: ByteVec)
         |  event TestEvent2(a: U256, b: I256, c: Address, d: Bool)
         |
         |  pub fn testEventTypes() -> (U256) {
         |    emit TestEvent1(4, -5i, @${address.toBase58}, toByteVec!(@${address.toBase58}))
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

    val logStatesOpt = getLogStates(blockFlow, contractId, 0)
    val logStates    = logStatesOpt.value

    logStates.blockHash is callingBlock.hash
    logStates.contractId is contractId
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
         |Contract Foo(tmp: U256) {
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

    val logStatesOpt = getLogStates(blockFlow, contractId, 0)
    val logStates    = logStatesOpt.value

    logStates.blockHash is callingBlock.hash
    logStates.contractId is contractId
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
      nextCount1.value is 1
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
      logStates2.contractId is contractId
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
         |Contract Foo(mut result: U256) {
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
      "Array type not supported for event \"Foo.TestEvent\""
    )
  }

  private def getLogStates(
      blockFlow: BlockFlow,
      contractId: ContractId,
      count: Int
  ): Option[LogStates] = {
    val logStatesId = LogStatesId(contractId, count)
    getLogStates(blockFlow, logStatesId)
  }

  private def getLogStatesByTxId(
      blockFlow: BlockFlow,
      txId: TransactionId
  ): AVector[LogStateRef] = {
    blockFlow.getEventsByHash(Byte32.unsafe(txId.bytes)).map(_.map(_._2)).rightValue
  }

  private def getLogStates(blockFlow: BlockFlow, logStatesId: LogStatesId): Option[LogStates] = {
    blockFlow
      .getEvents(logStatesId.contractId, logStatesId.counter, logStatesId.counter + 1)
      .map(_._2.headOption)
      .rightValue
  }

  it should "return contract id in contract creation" in new ContractFixture {
    val contract: String =
      s"""
         |Contract Foo(mut subContractId: ByteVec) {
         |  event Create(subContractId: ByteVec)
         |
         |  @using(preapprovedAssets = true, updateFields = true)
         |  pub fn foo() -> () {
         |    subContractId = copyCreateContract!{callerAddress!() -> ALPH: $minimalAlphInContract}(selfContractId!(), #010300)
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
      )._1

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> ALPH: 1 alph}()
         |}
         |
         |$contract
         |""".stripMargin
    val block = callTxScript(main)

    val logStatesOpt = getLogStates(blockFlow, contractId, 0)
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
         |Contract SubContract() {
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
    ): ContractId = {
      val contractRaw: String =
        s"""
           |Contract Foo(mut subContractId: ByteVec) {
           |  @using(preapprovedAssets = true, updateFields = true)
           |  pub fn createSubContract() -> () {
           |    subContractId = $createContractStmt
           |  }
           |
           |  pub fn callSubContract(path: ByteVec) -> () {
           |    let subContractIdCalculated = subContractId!(path)
           |    let subContractIdCalculatedTest = subContractIdOf!(Foo(selfContractId!()), path)
           |    assert!(subContractIdCalculated == subContractIdCalculatedTest, 0)
           |    assert!(subContractIdCalculated == subContractId, 0)
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
        )._1

      val createSubContractRaw: String =
        s"""
           |TxScript Main {
           |  Foo(#${contractId.toHexString}).createSubContract{callerAddress!() -> ALPH: 1 alph}()
           |}
           |$contractRaw
           |""".stripMargin

      callTxScript(createSubContractRaw)

      val subContractId = contractId.subContractId(serialize(subContractPath), chainIndex.from)
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
           |  Foo(#${contractId.toHexString}).callSubContract(#${subContractPathHex})
           |}
           |$contractRaw
           |""".stripMargin

      callTxScript(callSubContractRaw)

      subContractId
    }

    def verify(
        createContractStmt: String,
        failure: ExeFailure
    ): ContractId = {
      val contractRaw: String =
        s"""
           |Contract Foo(mut subContractId: ByteVec) {
           |  @using(preapprovedAssets = true, updateFields = true)
           |  pub fn createSubContract() -> () {
           |    subContractId = $createContractStmt
           |  }
           |}
           |$subContractRaw
           |""".stripMargin

      val contractId = createContract(contractRaw, AVector(Val.ByteVec(ByteString.empty)))._1

      val createSubContractRaw: String =
        s"""
           |TxScript Main {
           |  Foo(#${contractId.toHexString}).createSubContract{callerAddress!() -> ALPH: 1 alph}()
           |}
           |$contractRaw
           |""".stripMargin

      failCallTxScript(createSubContractRaw, failure)
      contractId
    }
    // scalastyle:on method.length
  }

  it should "check createSubContract and createSubContractWithToken" in new SubContractFixture {
    val subContract         = Compiler.compileContract(subContractRaw).rightValue
    val subContractByteCode = Hex.toHexString(serialize(subContract))

    {
      info("create sub-contract without token")
      val subContractPath1 = Hex.toHexString(serialize("nft-01"))
      verify(
        s"createSubContract!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath1, #$subContractByteCode, #$subContractInitialState)",
        subContractPath = "nft-01",
        numOfAssets = 2,
        numOfContracts = 2
      )
    }

    {
      info("create sub-contract with token")
      val subContractPath2 = Hex.toHexString(serialize("nft-02"))
      val subContractId = verify(
        s"createSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath2, #$subContractByteCode, #$subContractInitialState, 10)",
        subContractPath = "nft-02",
        numOfAssets = 5,
        numOfContracts = 4
      )
      val subContractTokenId = TokenId.unsafe(subContractId.value)
      val asset              = getContractAsset(subContractId, chainIndex)
      asset.tokens is AVector((subContractTokenId, U256.unsafe(10)))
    }

    {
      info("create sub-contract and transfer token to asset address")
      val subContractPath3 = Hex.toHexString(serialize("nft-03"))
      val subContractId = verify(
        s"createSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath3, #$subContractByteCode, #$subContractInitialState, 10, @${genesisAddress.toBase58})",
        subContractPath = "nft-03",
        numOfAssets = 8,
        numOfContracts = 6
      )
      val subContractTokenId = TokenId.unsafe(subContractId.value)
      val asset              = getContractAsset(subContractId, chainIndex)
      asset.tokens.length is 0

      val genesisTokenAmount =
        getTokenBalance(blockFlow, genesisAddress.lockupScript, subContractTokenId)
      genesisTokenAmount is 10
    }

    {
      info("create sub-contract and transfer token to contract address")
      val subContractPath4 = Hex.toHexString(serialize("nft-04"))
      val contractAddress  = Address.contract(ContractId.random)
      verify(
        s"createSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath4, #$subContractByteCode, #$subContractInitialState, 10, @${contractAddress.toBase58})",
        InvalidAssetAddress
      )
    }
  }

  it should "check copyCreateSubContract and copyCreateSubContractWithToken" in new SubContractFixture {
    val subContractId = createContractAndCheckState(subContractRaw, 2, 2, AVector.empty)._1

    {
      info("copy create sub-contract without token")
      val subContractPath1 = Hex.toHexString(serialize("nft-01"))
      verify(
        s"copyCreateSubContract!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath1, #${subContractId.toHexString}, #$subContractInitialState)",
        subContractPath = "nft-01",
        numOfAssets = 3,
        numOfContracts = 3
      )
    }

    {
      info("copy create sub-contract with token")
      val subContractPath2 = Hex.toHexString(serialize("nft-02"))
      val contractId = verify(
        s"copyCreateSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath2, #${subContractId.toHexString}, #$subContractInitialState, 10)",
        subContractPath = "nft-02",
        numOfAssets = 6,
        numOfContracts = 5
      )
      val tokenId = TokenId.unsafe(contractId.value)

      val asset = getContractAsset(contractId, chainIndex)
      asset.tokens is AVector((tokenId, U256.unsafe(10)))
    }

    {
      info("copy create sub-contract and transfer token to asset address")
      val subContractPath3 = Hex.toHexString(serialize("nft-03"))
      val contractId = verify(
        s"copyCreateSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath3, #${subContractId.toHexString}, #$subContractInitialState, 10, @${genesisAddress.toBase58})",
        subContractPath = "nft-03",
        numOfAssets = 9,
        numOfContracts = 7
      )
      val tokenId = TokenId.unsafe(contractId.value)
      val asset   = getContractAsset(contractId, chainIndex)
      asset.tokens.length is 0

      val genesisTokenAmount = getTokenBalance(blockFlow, genesisAddress.lockupScript, tokenId)
      genesisTokenAmount is 10
    }

    {
      info("copy create sub-contract and transfer token to contract address")
      val subContractPath4 = Hex.toHexString(serialize("nft-04"))
      val contractAddress  = Address.contract(ContractId.random)
      verify(
        s"copyCreateSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath4, #${subContractId.toHexString}, #$subContractInitialState, 10, @${contractAddress.toBase58})",
        InvalidAssetAddress
      )
    }
  }

  trait CheckArgAndReturnLengthFixture extends ContractFixture {
    val upgradable: String =
      s"""
         |Abstract Contract Upgradable() {
         |  pub fn upgrade(code: ByteVec) -> () {
         |    migrate!(code)
         |  }
         |}
         |""".stripMargin

    def foo: String
    lazy val fooIdHex = createContract(foo, AVector.empty)._1.toHexString

    def upgrade() = {
      val fooV1 =
        s"""
           |Contract Foo() extends Upgradable() {
           |  pub fn foo() -> () {}
           |}
           |$upgradable
           |""".stripMargin
      val fooV1Bytecode = serialize(Compiler.compileContract(fooV1).rightValue)
      val upgradeFooScript: String =
        s"""
           |TxScript Main {
           |  let foo = Foo(#$fooIdHex)
           |  foo.upgrade(#${Hex.toHexString(fooV1Bytecode)})
           |}
           |$foo
           |""".stripMargin
      callTxScript(upgradeFooScript)
    }
  }

  it should "check external method arg length" in new CheckArgAndReturnLengthFixture {
    val foo: String =
      s"""
         |Contract Foo() extends Upgradable() {
         |  pub fn foo(array: [U256; 2]) -> () {
         |    let _ = array
         |  }
         |}
         |$upgradable
         |""".stripMargin

    val script: String =
      s"""
         |TxScript Main {
         |  let foo = Foo(#$fooIdHex)
         |  foo.foo([0, 0])
         |}
         |$foo
         |""".stripMargin

    callTxScript(script)
    upgrade()
    failCallTxScript(script, InvalidExternalMethodArgLength)
  }

  it should "check external method return length" in new CheckArgAndReturnLengthFixture {
    val foo: String =
      s"""
         |Contract Foo() extends Upgradable() {
         |  pub fn foo() -> (Bool, [U256; 2]) {
         |    return false, [0; 2]
         |  }
         |}
         |$upgradable
         |""".stripMargin

    val script: String =
      s"""
         |TxScript Main {
         |  let foo = Foo(#$fooIdHex)
         |  let (_, _) = foo.foo()
         |}
         |$foo
         |""".stripMargin

    callTxScript(script)
    upgrade()
    failCallTxScript(script, InvalidExternalMethodReturnLength)
  }

  it should "not load contract just after creation" in new ContractFixture {
    val contract: String =
      s"""
         |Contract Foo(mut subContractId: ByteVec) {
         |  @using(preapprovedAssets = true, updateFields = true)
         |  pub fn foo() -> () {
         |    subContractId = copyCreateContract!{
         |      callerAddress!() -> ALPH: ${ALPH.nanoAlph(1000).v}
         |    }(selfContractId!(), #010300)
         |    let subContract = Foo(subContractId)
         |    subContract.foo{callerAddress!() -> ALPH: ${ALPH.nanoAlph(1000).v}}()
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(contract, 2, 2, AVector(Val.ByteVec(ByteString.empty)))._1

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> ALPH: 1 alph}()
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
         |Contract Foo() {
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
         |Contract Bar() {
         |  pub fn bar(fooId: ByteVec) -> () {
         |    let foo = Foo(fooId)
         |    foo.destroy()
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(s"$foo\n$bar", AVector.empty)._1
    val barId = createContract(s"$bar\n$foo", AVector.empty)._1

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
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    let bytes = encodeToByteVec!(true, 1, false)
         |    assert!(bytes == #03000102010000, 0)
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo, AVector.empty)._1
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
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin
    val fooId      = createContract(foo, AVector.empty)._1
    val fooAddress = Address.contract(fooId).toBase58

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo()
         |  transferToken!(callerAddress!(), @${fooAddress}, ALPH, 1 alph)
         |}
         |
         |$foo
         |""".stripMargin
    failCallTxScript(main, PayToContractAddressNotInCallerTrace)
  }

  it should "work with interface" in new ContractFixture {

    {
      info("works with single inheritance")

      val interface =
        s"""
           |Interface I {
           |  @using(checkExternalCaller = false)
           |  pub fn f1() -> U256
           |  @using(checkExternalCaller = false)
           |  pub fn f2() -> U256
           |  @using(checkExternalCaller = false)
           |  pub fn f3() -> ByteVec
           |}
           |""".stripMargin

      val contract =
        s"""
           |Contract Foo() implements I {
           |  @using(checkExternalCaller = false)
           |  pub fn f3() -> ByteVec {
           |    return #00
           |  }
           |
           |  @using(checkExternalCaller = false)
           |  pub fn f2() -> U256 {
           |    return 2
           |  }
           |
           |  @using(checkExternalCaller = false)
           |  pub fn f1() -> U256 {
           |    return 1
           |  }
           |}
           |
           |$interface
           |""".stripMargin

      val contractId = createContract(contract, AVector.empty)._1

      val main =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  let impl = I(#${contractId.toHexString})
           |  assert!(impl.f1() == 1, 0)
           |  assert!(impl.f2() == 2, 0)
           |  assert!(impl.f3() == #00, 0)
           |}
           |
           |$interface
           |""".stripMargin

      callTxScript(main)
    }

    {
      info("fail with multiple inheritance")

      val interface1 =
        s"""
           |Interface I1 {
           |  pub fn f1() -> U256
           |}
           |""".stripMargin

      val interface2 =
        s"""
           |Interface I2 {
           |  pub fn f2() -> U256
           |}
           |""".stripMargin

      val contract =
        s"""
           |Contract Foo() implements I1, I2 {
           |  pub fn f2() -> U256 {
           |    return 2
           |  }
           |
           |  pub fn f1() -> U256 {
           |    return 1
           |  }
           |}
           |
           |$interface1
           |$interface2
           |""".stripMargin

      Compiler
        .compileContract(contract)
        .leftValue
        .message is "Contract only supports implementing single interface: I1, I2"
    }
  }

  it should "not instantiate with abstract contract" in new ContractFixture {
    val abstractContract =
      s"""
         |Abstract Contract AC() {
         |  pub fn f1() -> U256 {
         |    return 1
         |  }
         |  pub fn f2() -> U256
         |}
         |""".stripMargin

    val contract =
      s"""
         |Contract Foo() implements AC {
         |  pub fn f2() -> U256 {
         |    return 2
         |  }
         |}
         |
         |$abstractContract
         |""".stripMargin

    val contractId = createContract(contract, AVector.empty)._1

    val main =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let impl = AC(#${contractId.toHexString})
         |}
         |
         |$abstractContract
         |""".stripMargin

    Compiler
      .compileTxScript(main)
      .leftValue
      .message is "AC is not instantiable"
  }

  it should "not inherit from the non-abstract contract" in new ContractFixture {
    val nonAbstractContract =
      s"""
         |Contract C() {
         |  pub fn f1() -> U256 {
         |    return 1
         |  }
         |}
         |""".stripMargin

    val contract =
      s"""
         |Contract Foo() implements C {
         |  pub fn f2() -> U256 {
         |    return 2
         |  }
         |}
         |
         |$nonAbstractContract
         |""".stripMargin

    Compiler
      .compileContract(contract)
      .leftValue
      .message is "Contract C can not be inherited"
  }

  it should "inherit interface events" in new ContractFixture {
    val foo: String =
      s"""
         |Interface Foo {
         |  event Foo(x: U256)
         |  @using(checkExternalCaller = false)
         |  pub fn foo() -> ()
         |}
         |""".stripMargin
    val bar: String =
      s"""
         |Contract Bar() implements Foo {
         |  event Bar(x: U256)
         |  @using(checkExternalCaller = false)
         |  pub fn foo() -> () {
         |    emit Foo(1)
         |    emit Bar(2)
         |  }
         |}
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector.empty)._1

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

    val logStatesOpt = getLogStates(blockFlow, barId, 0)
    val logStates    = logStatesOpt.value
    logStates.blockHash is block.hash
    logStates.states.length is 2
    logStates.states(0).fields.head.asInstanceOf[Val.U256].v.toIntUnsafe is 1
    logStates.states(1).fields.head.asInstanceOf[Val.U256].v.toIntUnsafe is 2
  }

  it should "not be able to transfer assets right after contract is created" in new ContractFixture {
    val foo: String =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |  }
         |}
         |""".stripMargin

    val fooContract     = Compiler.compileContract(foo).rightValue
    val fooByteCode     = Hex.toHexString(serialize(fooContract))
    val fooInitialState = Hex.toHexString(serialize(AVector.empty[Val]))

    def createFooContract(transferAlph: Boolean): String = {
      val maybeTransfer = if (transferAlph) {
        s"transferTokenFromSelf!(contractAddress, ALPH, ${minimalAlphInContract.v})"
      } else {
        ""
      }

      val bar: String =
        s"""
           |Contract Bar() {
           |  @using(preapprovedAssets = true, assetsInContract = $transferAlph)
           |  pub fn bar() -> () {
           |    let contractId = createContract!{@$genesisAddress -> ALPH: $minimalAlphInContract}(#$fooByteCode, #$fooInitialState)
           |    let contractAddress = contractIdToAddress!(contractId)
           |
           |    $maybeTransfer
           |  }
           |}
           |""".stripMargin

      val barContractId =
        createContract(
          bar,
          AVector.empty,
          initialAttoAlphAmount = ALPH.alph(2)
        )._1

      s"""
         |TxScript Main {
         |  let bar = Bar(#${barContractId.toHexString})
         |  bar.bar{@$genesisAddress -> ALPH: $minimalAlphInContract}()
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
           |  transferToken!(caller, @${randomContract}, ALPH, 0.01 alph)
           |}
           |""".stripMargin
      failCallTxScript(script, PayToContractAddressNotInCallerTrace)
    }

    {
      info("Transfer to random contract address in Contract")

      val foo: String =
        s"""
           |Contract Foo() {
           |  @using(assetsInContract = true)
           |  pub fn foo() -> () {
           |    transferTokenFromSelf!(@${randomContract}, ALPH, 0.01 alph)
           |  }
           |}
           |""".stripMargin
      val fooId      = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(2))._1
      val fooAddress = Address.contract(fooId)

      val script =
        s"""
           |TxScript Main {
           |  let caller = callerAddress!()
           |  transferToken!(caller, @${fooAddress}, ALPH, 0.01 alph)
           |  let foo = Foo(#${fooId.toHexString})
           |  foo.foo()
           |}
           |
           |$foo
           |""".stripMargin
      failCallTxScript(script, PayToContractAddressNotInCallerTrace)
    }

    {
      info("Transfer to one of the caller addresses in Contract")
      val foo: String =
        s"""
           |Contract Foo() {
           |  @using(assetsInContract = true)
           |  pub fn foo(to: Address) -> () {
           |    transferTokenFromSelf!(to, ALPH, 0.01 alph)
           |  }
           |}
           |""".stripMargin
      val fooId = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(2))._1

      val bar: String =
        s"""
           |Contract Bar(index: U256, nextBarId: ByteVec) {
           |  @using(assetsInContract = true)
           |  pub fn bar(to: Address) -> () {
           |    if (index == 0) {
           |      let foo = Foo(#${fooId.toHexString})
           |      foo.foo(to)
           |    } else {
           |      let bar = Bar(nextBarId)
           |      transferTokenFromSelf!(selfAddress!(), ALPH, 0) // dirty hack
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
        val barId = createContract(bar, initialFields, initialAttoAlphAmount = ALPH.alph(2))._1
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

  it should "test the special case (1)" in new ContractFixture {
    val foo: String =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    bar{selfAddress!() -> ALPH: 0.1 alph}()
         |  }
         |
         |  @using(preapprovedAssets = true)
         |  pub fn bar() -> () {
         |    transferTokenToSelf!(selfAddress!(), ALPH, 0.1 alph)
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo, AVector.empty, initialAttoAlphAmount = ALPH.alph(2))._1

    val script =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  foo.foo()
         |}
         |
         |$foo
         |""".stripMargin
    callTxScript(script)

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractAsset(fooId).rightValue.amount is ALPH.alph(2)
  }

  it should "test AssertWithErrorCode instruction" in new ContractFixture {
    val foo: String =
      s"""
         |Contract Foo() {
         |  enum ErrorCodes {
         |    Error = 0
         |  }
         |  pub fn foo() -> () {
         |    assert!(false, ErrorCodes.Error)
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo, AVector.empty)._1

    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  foo.foo()
         |}
         |$foo
         |""".stripMargin
    val script = Compiler.compileTxScript(main).rightValue
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script)).getMessage is
      s"Right(TxScriptExeFailed(AssertionFailedWithErrorCode(${Address.contract(fooId).toBase58},0)))"
  }

  it should "test Contract type" in new ContractFixture {
    val foo: String =
      s"""
         |Contract Foo(x: U256) {
         |  pub fn foo() -> U256 {
         |    return x
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo, AVector(Val.U256(123)))._1

    val bar: String =
      s"""
         |Contract Bar() {
         |  pub fn bar(foo: Foo) -> U256 {
         |    return foo.foo()
         |  }
         |}
         |
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector.empty)._1

    val script =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  let bar = Bar(#${barId.toHexString})
         |  let x = bar.bar(foo)
         |  assert!(x == 123, 0)
         |}
         |
         |$bar
         |""".stripMargin
    callTxScript(script)
  }

  it should "update contract output ref for every TX" in new ContractFixture {
    val foo: String =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn foo() -> () {
         |    transferTokenToSelf!(callerAddress!(), ALPH, 0.01 alph)
         |  }
         |}
         |""".stripMargin
    val (fooId, fooOutputRef) = createContract(foo, AVector.empty)

    val script =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  foo.foo{callerAddress!() -> ALPH: 1 alph}()
         |}
         |$foo
         |""".stripMargin
    val block      = callTxScript(script)
    val tx         = block.nonCoinbase.head
    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    val fooOutputRefNew = worldState.getContractState(fooId).rightValue.contractOutputRef
    fooOutputRefNew isnot fooOutputRef

    val fooOutputNew = worldState.getContractOutput(fooOutputRefNew).rightValue
    fooOutputRefNew is ContractOutputRef.from(tx.id, fooOutputNew, 0)
    fooOutputRefNew.asInstanceOf[TxOutputRef] is TxOutputRef.unsafe(tx, 0)
  }

  it should "test debug function" in new EventFixture {
    override lazy val initialState: AVector[Val] = AVector(Val.ByteVec.fromString("Alephium"))
    override def contractRaw: String =
      s"""
         |Contract Foo(name: ByteVec) {
         |  pub fn foo() -> () {
         |    emit Debug(`Hello, $${name}!`)
         |  }
         |}
         |""".stripMargin

    override def callingScriptRaw: String =
      s"""
         |$contractRaw
         |
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo()
         |}
         |""".stripMargin

    val logStates = getLogStates(blockFlow, contractId, 0).value
    logStates.blockHash is callingBlock.hash
    logStates.states.length is 1
    val event = logStates.states.head
    event.index is debugEventIndex.v.v.toInt.toByte
    event.fields is AVector[Val](Val.ByteVec(ByteString.fromString("Hello, Alephium!")))
  }

  it should "test contract asset only function" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    assert!(tokenRemaining!(selfAddress!(), ALPH) == 1 alph, 0)
         |  }
         |}
         |""".stripMargin
    val contractId = createContract(foo, AVector.empty)._1

    val script =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main() {
         |  Foo(#${contractId.toHexString}).foo()
         |}
         |$foo
         |""".stripMargin
    testSimpleScript(script)
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
      countOpt   <- worldState.logStorage.logCounterState.getOpt(contractId)
    } yield countOpt).rightValue
  }
}
// scalastyle:on file.size.limit no.equal regex
