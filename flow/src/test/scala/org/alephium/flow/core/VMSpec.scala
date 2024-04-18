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
import java.nio.charset.StandardCharsets

import akka.util.ByteString
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.crypto._
import org.alephium.flow.FlowFixture
import org.alephium.flow.mempool.MemPool.AddedToMemPool
import org.alephium.flow.validation.{TxScriptExeFailed, TxValidation}
import org.alephium.protocol.{vm, ALPH, Generators, Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.ralph.Compiler
import org.alephium.serde.{serialize, Serde}
import org.alephium.util._

// scalastyle:off file.size.limit method.length number.of.methods
class VMSpec extends AlephiumSpec with Generators {

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
    lazy val script0          = Compiler.compileContract(input0).rightValue
    lazy val initialMutFields = AVector[Val](Val.U256(U256.Zero))

    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val fromLockup = getGenesisLockupScript(chainIndex)
    lazy val txScript0 =
      contractCreation(script0, AVector.empty, initialMutFields, fromLockup, ALPH.alph(1))
    lazy val block0 = payableCall(blockFlow, chainIndex, txScript0)
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
    checkState(
      blockFlow,
      chainIndex,
      contractId0,
      immFields = AVector.empty,
      initialMutFields,
      contractOutputRef0
    )

    val script1 = Compiler.compileTxScript(input1, 1).rightValue
    intercept[AssertionError](simpleScript(blockFlow, chainIndex, script1)).getMessage is
      s"Right(TxScriptExeFailed($ExternalPrivateMethodCall))"
  }

  it should "handle contract states" in new CallFixture {
    val access: String = "pub"

    addAndCheck(blockFlow, block0, 1)
    checkState(
      blockFlow,
      chainIndex,
      contractId0,
      immFields = AVector.empty,
      initialMutFields,
      contractOutputRef0
    )

    val script1   = Compiler.compileTxScript(input1, 1).rightValue
    val newState1 = AVector[Val](Val.U256(U256.unsafe(10)))
    val block1    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block1, 2)
    checkState(
      blockFlow,
      chainIndex,
      contractId0,
      immFields = AVector.empty,
      newState1,
      contractOutputRef0,
      numAssets = 4
    )

    val newState2 = AVector[Val](Val.U256(U256.unsafe(20)))
    val block2    = simpleScript(blockFlow, chainIndex, script1)
    addAndCheck(blockFlow, block2, 3)
    checkState(
      blockFlow,
      chainIndex,
      contractId0,
      immFields = AVector.empty,
      newState2,
      contractOutputRef0,
      numAssets = 6
    )
  }

  trait ContractFixture extends FlowFixture {
    lazy val chainIndex     = ChainIndex.unsafe(0, 0)
    lazy val genesisLockup  = getGenesisLockupScript(chainIndex)
    lazy val genesisAddress = Address.Asset(genesisLockup)

    def createContractAndCheckState(
        input: String,
        numAssets: Int,
        numContracts: Int,
        initialImmState: AVector[Val] = AVector.empty,
        initialMutState: AVector[Val] = AVector[Val](Val.U256(U256.Zero)),
        tokenIssuanceInfo: Option[TokenIssuance.Info] = None,
        initialAttoAlphAmount: U256 = minimalContractStorageDeposit(
          networkConfig.getHardFork(TimeStamp.now())
        )
    ): (ContractId, ContractOutputRef) = {
      val (contractId, contractOutputRef) =
        createContract(
          input,
          initialImmState,
          initialMutState,
          tokenIssuanceInfo,
          initialAttoAlphAmount
        )

      checkState(
        blockFlow,
        chainIndex,
        contractId,
        initialImmState,
        initialMutState,
        contractOutputRef,
        numAssets,
        numContracts
      )

      (contractId, contractOutputRef)
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

    def failCallTxScript(script: StatefulScript, failure: ExeFailure) = {
      intercept[AssertionError](callCompiledTxScript(script)).getMessage is
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
        code: String,
        contractAssetRef: ContractOutputRef,
        existed: Boolean
    ): Assertion = {
      val contract    = Compiler.compileContract(code).rightValue
      val worldState  = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      val contractKey = ContractId.from(Hex.from(contractId).get).get
      worldState.contractState.exists(contractKey) isE existed
      worldState.outputState.exists(contractAssetRef) isE existed
      worldState.codeState.exists(contract.hash) isE false
      worldState.contractImmutableState.exists(contract.hash) isE true // keep history state always
    }

    def getContractAsset(
        contractId: ContractId,
        chainIndex: ChainIndex = chainIndex
    ): ContractOutput = {
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).rightValue
      worldState.getContractAsset(contractId).rightValue
    }

    def deployAndCheckContractState(
        script: String,
        immFields: AVector[Val],
        mutFields: AVector[Val]
    ) = {
      val block      = callTxScript(script)
      val tx         = block.nonCoinbase.head
      val contractId = ContractId.from(tx.id, tx.unsigned.fixedOutputs.length, tx.fromGroup)
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val contractState = worldState.getContractState(contractId).rightValue
      contractState.immFields is immFields
      contractState.mutFields is mutFields
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
    val fooContractId = createContract(fooV0Code)._1.toHexString

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
        initialImmState = AVector.empty,
        initialMutState = AVector.empty,
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
        initialImmState = AVector.empty,
        initialMutState = AVector.empty,
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
      val contractAddress  = Address.contract(ContractId.random)
      val encodedMutFields = Hex.toHexString(serialize[AVector[Val]](AVector.empty))

      val script: String =
        s"""
           |TxScript Main {
           |  createContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractByteCode, #, #$encodedMutFields, 1, @${contractAddress.toBase58})
           |}
           |""".stripMargin

      failCallTxScript(script, InvalidAssetAddress(contractAddress))
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
         |    transferTokenFromSelf!(sender, ALPH, 0.1 alph)
         |    assert!(tokenRemaining!(selfAddress!(), ALPH) == contractAlph - 0.1 alph, 0)
         |    transferToken!(sender, selfAddress!(), ALPH, 1 alph)
         |    assert!(tokenRemaining!(sender, ALPH) == senderAlph - 2 alph, 0)
         |  }
         |}
         |""".stripMargin
    val fooId = createContract(foo)._1

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
         |    transferTokenFromSelf!(to, selfTokenId!(), amount)
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
      initialImmState = AVector.empty,
      initialMutState = AVector.empty,
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
      dustUtxoAmount
    )
  }

  it should "enforce using contract assets" in new ContractFixture {
    val bar =
      s"""
         |Contract Bar() {
         |  @using(assetsInContract = true)
         |  pub fn bar() -> () {
         |    transferTokenFromSelf!(callerAddress!(), ALPH, minimalContractDeposit!())
         |  }
         |}
         |""".stripMargin
    val (barContractId, _) = createContract(bar, initialAttoAlphAmount = minimalAlphInContract * 2)
    getContractAsset(barContractId).amount is minimalAlphInContract * 2

    val foo =
      s"""
         |Contract Foo(bar: Bar) {
         |  @using(assetsInContract = enforced)
         |  pub fn foo() -> () {
         |    bar.bar()
         |  }
         |}
         |$bar
         |""".stripMargin
    val (fooContractId, _) = createContract(foo, AVector(Val.ByteVec(barContractId.bytes)))
    getContractAsset(fooContractId).amount is minimalAlphInContract

    val script =
      s"""
         |TxScript Main {
         |  Foo(#${fooContractId.toHexString}).foo()
         |}
         |$foo
         |""".stripMargin
    callTxScript(script, chainIndex)
    getContractAsset(barContractId).amount is minimalAlphInContract
    getContractAsset(fooContractId).amount is minimalAlphInContract * 2
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
      createContract(
        contract,
        AVector.empty,
        AVector.empty,
        Some(TokenIssuance.Info(ALPH.alph(5)))
      )._1
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
        createContract(
          token,
          AVector.empty,
          AVector.empty,
          Some(TokenIssuance.Info(ALPH.alph(100)))
        )._1
      )
    val _tokenId1 =
      TokenId.from(
        createContract(
          token,
          AVector.empty,
          AVector.empty,
          Some(TokenIssuance.Info(ALPH.alph(100)))
        )._1
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

    val timestamp0 = TimeStamp.now().plusHoursUnsafe(1)
    val timestamp1 = TimeStamp.now().plusHoursUnsafe(2)
    val timestamp2 = TimeStamp.now().plusHoursUnsafe(3)
    val lock =
      s"""
         |TxScript Main {
         |  let timestamp0 = ${timestamp0.millis}
         |  let timestamp1 = ${timestamp1.millis}
         |  let timestamp2 = ${timestamp2.millis}
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

    def checkOutput(
        index: Int,
        timestamp: TimeStamp,
        alphAmount: U256,
        tokens: (TokenId, U256)*
    ) = {
      tx.generatedOutputs(index) is AssetOutput(
        alphAmount,
        genesisAddress.lockupScript,
        timestamp,
        AVector.from(tokens),
        ByteString.empty
      )
    }

    checkOutput(0, timestamp0, ALPH.cent(1))
    checkOutput(1, timestamp1, dustUtxoAmount, tokenId0 -> ALPH.cent(3))
    checkOutput(2, timestamp1, ALPH.cent(2) - dustUtxoAmount)
    checkOutput(3, timestamp2, dustUtxoAmount, tokenId0 -> ALPH.cent(5))
    checkOutput(4, timestamp2, dustUtxoAmount, tokenId1 -> ALPH.cent(6))
    checkOutput(5, timestamp2, ALPH.cent(4) - (dustUtxoAmount * 2))
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

    val contractId = createContractAndCheckState(input, 2, 2, initialMutState = AVector.empty)._1

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
    val contractId1 = createContractAndCheckState(
      input1,
      3,
      3,
      initialImmState = AVector.empty,
      initialMutState = AVector.empty
    )._1

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
    val newMutFields = AVector[Val](Val.U256(U256.unsafe(110)))
    testSimpleScript(main)

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    worldState.getContractStates().rightValue.length is 3

    checkState(
      blockFlow,
      chainIndex,
      contractKey0,
      immFields = AVector.empty,
      newMutFields,
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
      initialImmState = AVector.empty,
      initialMutState = AVector.empty
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
         |    assert!(3 ** 0 + 1 == 2, 0)
         |    assert!(3 ** 3 - 1 == 26, 0)
         |    assert!(3 * 3 ** 2 == 27, 0)
         |    assert!(10 ** 18 == 1 alph, 0)
         |    assert!(-3 ** 2 == 9i, 0)
         |    assert!(-3 ** 3 == -27, 0)
         |    assert!(8 / 2 ** 2 + 1 == 3, 0)
         |    assert!(2 |**| 256 == 0, 0)
         |    assert!(8 / 2 |**| 2 - 1 == 1, 0)
         |    let a = 2 ** 255 + 1
         |    assert!(a |**| 3 == a, 0)
         |
         |    assert!(mulModN!(2, 3, 4) == 2, 0)
         |    assert!(mulModN!(1 << 128, 1 << 128, u256Max!()) == 1, 0)
         |    assert!(mulModN!(1 << 128, 1 << 128, u256Max!() - 1) == 2, 0)
         |    assert!(mulModN!(u256Max!(), u256Max!(), u256Max!()) == 0, 0)
         |
         |    assert!(addModN!(2, 3, 4) == 1, 0)
         |    assert!(addModN!(1 << 128, 1 << 128, u256Max!()) == 1 << 129, 0)
         |    assert!(addModN!(1 << 255, 1 << 255, u256Max!()) == 1, 0)
         |    assert!(addModN!(u256Max!(), u256Max!(), u256Max!()) == 0, 0)
         |    assert!(addModN!(u256Max!(), 1, u256Max!()) == 1, 0)
         |    assert!(addModN!(1, u256Max!(), u256Max!()) == 1, 0)
         |
         |    assert!(u256Max!() == ${U256.MaxValue}, 0)
         |    assert!(i256Max!() == ${I256.MaxValue}i, 0)
         |    assert!(i256Min!() == ${I256.MinValue}i, 0)
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
         |  assert!(ALPH == zeros!(32), 0)
         |  assert!(ALPH == #0000000000000000000000000000000000000000000000000000000000000000, 0)
         |}
         |""".stripMargin

    testSimpleScript(main)
  }

  it should "test minimalContractDeposit and mapEntryDeposit" in new ContractFixture {
    val main: String =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(minimalContractDeposit!() == 0.1 alph, 0)
         |  assert!(mapEntryDeposit!() == minimalContractDeposit!(), 0)
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

    val contractId = createContract(fooContract)._1.toHexString

    val encodedState     = Hex.toHexString(serialize[AVector[Val]](AVector.empty))
    val encodedImmFields = encodedState
    val encodedMutFields = encodedState
    val tokenAmount      = ALPH.oneNanoAlph

    {
      info("copy create contract with token")
      val script: String =
        s"""
           |TxScript Main {
           |  copyCreateContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractId, #$encodedImmFields, #$encodedMutFields, ${tokenAmount.v})
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
           |  copyCreateContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractId, #$encodedImmFields, #$encodedMutFields, ${tokenAmount.v}, @${genesisAddress.toBase58})
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
           |  copyCreateContractWithToken!{ @$genesisAddress -> ALPH: 1 alph }(#$contractId, #$encodedImmFields, #$encodedMutFields, ${tokenAmount.v}, @${contractAddress.toBase58})
           |}
           |""".stripMargin

      failCallTxScript(script, InvalidAssetAddress(contractAddress))
    }
  }

  // scalastyle:off no.equal
  it should "test contract instructions" in new ContractFixture {
    def createContractExtended(input: String): (String, String, String, String) = {
      val contractId    = createContract(input)._1
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
    val (fooId, _, fooHash, fooCodeHash) = createContractExtended(foo)

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
    val (barId, barAddress, barHash, barCodeHash) = createContractExtended(bar)

    def main(state: String) =
      s"""
         |TxScript Main {
         |  Bar(#$barId).bar{ @$genesisAddress -> ALPH: 1 alph }(#$fooId, #$fooHash, #$fooCodeHash, #$barId, #$barHash, #$barCodeHash, @$barAddress)
         |  copyCreateContract!{ @$genesisAddress -> ALPH: 1 alph }(#$fooId, #00, #$state)
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

  it should "test addressToContractId builtin" in new ContractFixture with LockupScriptGenerators {
    def success(lockupScript: LockupScript.P2C) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let address = @${Address.from(lockupScript).toBase58}
         |  assert!(addressToContractId!(address) == #${lockupScript.contractId.toHexString}, 0)
         |}
         |""".stripMargin

    forAll(p2cLockupGen(GroupIndex.unsafe(0))) { lockupScript =>
      testSimpleScript(success(lockupScript))
    }

    def failure(lockupScript: LockupScript.Asset) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  addressToContractId!(@${Address.from(lockupScript).toBase58})
         |}
         |""".stripMargin

    forAll(assetLockupGen(GroupIndex.unsafe(0))) { lockupScript =>
      failSimpleScript(failure(lockupScript), AssertionFailed)
    }
  }

  it should "test groupOfAddress builtin" in new ContractFixture {
    override val configValues =
      Map(("alephium.broker.groups", 4), ("alephium.broker.broker-num", 1))

    val script =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  assert!(groupOfAddress!(@226T1XspViny5o6Ce1jQR6UCGrDXuq5NBVoCFNufMEWBZ) == 0, 0)
         |  assert!(groupOfAddress!(@14UAjZ3qcmEVKdTo84Kwf4RprTQi86w2TefnnGFjov9xF) == 1, 0)
         |  assert!(groupOfAddress!(@qeKk7r92Vn2Xjn4GcMEcJ2EwVfVs27kWUpptrWcWsUWC) == 2, 0)
         |  assert!(groupOfAddress!(@Wz8UJ2YZqQxBN2Af9fvTpDQR3daZUC3hpPrc6omCP2U3iYqAxCVPCdPtgocRTsZfYrgvswf63DUyLda4QKhmGtzkpwcutG2SwReiv6p7SQhkxYfQT3S2cFGGyqkbAvoamqwcJD) == 3, 0)
         |}
         |""".stripMargin

    testSimpleScript(script)
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
    val contractId = createContract(foo)._1.toHexString

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
        initialMutState: AVector[Val] = AVector.empty,
        initialAttoAlphAmount: U256 = minimalAlphInContract
    ): (String, ContractOutputRef) = {
      val contractId =
        createContract(
          contract,
          AVector.empty,
          initialMutState,
          initialAttoAlphAmount = initialAttoAlphAmount
        )._1
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
    checkContractState(fooId, foo, fooAssetRef, true)
    val fooAddress = Address.contract(ContractId.unsafe(Hash.unsafe(Hex.unsafe(fooId))))

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
      fail(blockFlow, chainIndex, script, PayToContractAddressNotInCallerTrace(address))
      checkContractState(fooId, foo, fooAssetRef, true)
    }

    {
      info("Destroy a contract and and transfer value to itself")
      val script = Compiler.compileTxScript(destroy(fooAddress.toBase58)).rightValue
      fail(blockFlow, chainIndex, script, ContractAssetAlreadyFlushed)
      checkContractState(fooId, foo, fooAssetRef, true)
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
        .startsWith(
          s"Right(TxScriptExeFailed(Contract ${fooAddress.toBase58} does not exist"
        ) is true
      checkContractState(
        fooId,
        foo,
        fooAssetRef,
        true
      ) // None of the two destruction will take place
    }

    {
      info("Destroy a contract properly")
      callTxScript(destroy(genesisAddress.toBase58))
      checkContractState(fooId, foo, fooAssetRef, false)
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
    fooCallerAssetBefore.amount is minimalAlphInContract

    callTxScript(destroy())
    checkContractState(fooId, foo, fooAssetRef, false)

    val fooCallerAssetAfter = getContractAsset(fooCallerContractId, chainIndex)
    fooCallerAssetAfter.amount is minimalAlphInContract.mulUnsafe(2)
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
    checkContractState(fooId, foo, fooAssetRef, false)
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
      val fooId = createContract(foo, initialAttoAlphAmount = ALPH.alph(10))._1
      failCallTxScript(main(fooId, foo), error)
    }

    test(useAssetsInContract = true, ContractAssetAlreadyFlushed)
    test(useAssetsInContract = false, NoBalanceAvailable)
  }

  it should "migrate contract" in new DestroyFixture {
    val fooV1 =
      s"""
         |Contract Foo(mut x: Bool) {
         |  @using(updateFields = true)
         |  pub fn foo(code: ByteVec, changeState: Bool) -> () {
         |    // in practice, we should check the permission for migration
         |    if (!changeState) {
         |      migrate!(code)
         |    } else {
         |      migrateWithFields!(code, #00, #010000)
         |    }
         |  }
         |
         |  pub fn checkX(expected: Bool) -> () {
         |    assert!(x == expected, 0)
         |  }
         |
         |  pub fn updateState() -> () {
         |    x = false
         |  }
         |}
         |""".stripMargin
    val (fooId, _) = prepareContract(fooV1, AVector[Val](Val.True))
    val fooV2 =
      s"""
         |Contract Foo(mut x: Bool) {
         |  @using(updateFields = true)
         |  pub fn foo(code: ByteVec, changeState: Bool) -> () {
         |    if (changeState) {
         |      migrateWithFields!(code, #00, #010000)
         |    } else {
         |      migrate!(code)
         |    }
         |  }
         |
         |  pub fn checkX(expected: Bool) -> () {
         |    assert!(x == expected, 0)
         |  }
         |
         |  pub fn updateState() -> () {
         |    x = false
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
      obj.initialMutFields is AVector[Val](Val.True)
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
      obj.initialMutFields is AVector[Val](Val.False)
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
    checkContractState(fooId, foo, fooAssetRef, true)

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
    val barId = createContract(bar)._1.toHexString

    val main =
      s"""
         |TxScript Main {
         |  Bar(#$barId).bar(@${genesisAddress.toBase58})
         |}
         |
         |$bar
         |""".stripMargin

    callTxScript(main)
    checkContractState(fooId, foo, fooAssetRef, false)
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
    checkContractState(fooId, foo, fooAssetRef, true)

    val main =
      s"""
         |TxScript Main {
         |  Foo(#$fooId).foo(@${genesisAddress.toBase58})
         |}
         |
         |$foo
         |""".stripMargin
    failCallTxScript(main, ContractDestructionShouldNotBeCalledFromSelf)
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
         |  assert!(txGasPrice!() == ${nonCoinbaseMinGasPrice.value}, 0)
         |  assert!(txGasAmount!() == ${gasAmount.value}, 0)
         |  assert!(txGasFee!() == ${nonCoinbaseMinGasPrice * gasAmount}, 0)
         |}
         |""".stripMargin
    testSimpleScript(main(0), gasAmount.value)
    failSimpleScript(main(1), InvalidTxInputIndex(1))
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
    val (bip340Pri, bip340Pub)   = BIP340Schnorr.generatePriPub()
    val bip340Sig                = BIP340Schnorr.sign(Hash.zero.bytes, bip340Pri).toHexString
    def main(p256Sig: String, ed25519Sig: String, bip340Sig: String) =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  verifySecP256K1!(#$zero, #${p256Pub.toHexString}, #$p256Sig)
         |  verifyED25519!(#$zero, #${ed25519Pub.toHexString}, #$ed25519Sig)
         |  verifyBIP340Schnorr!(#$zero, #${bip340Pub.toHexString}, #$bip340Sig)
         |}
         |""".stripMargin
    testSimpleScript(main(p256Sig, ed25519Sig, bip340Sig))
    val randomSecP256K1Signature = SecP256K1Signature.generate
    failSimpleScript(
      main(randomSecP256K1Signature.toHexString, ed25519Sig, bip340Sig),
      InvalidSignature(
        p256Pub.bytes,
        Hash.zero.bytes,
        randomSecP256K1Signature.bytes
      )
    )
    val randomEd25519Signature = ED25519Signature.generate
    failSimpleScript(
      main(p256Sig, randomEd25519Signature.toHexString, bip340Sig),
      InvalidSignature(
        ed25519Pub.bytes,
        Hash.zero.bytes,
        randomEd25519Signature.bytes
      )
    )
    val randomBIP340SchnorrSignature = BIP340SchnorrSignature.generate
    failSimpleScript(
      main(p256Sig, ed25519Sig, randomBIP340SchnorrSignature.toHexString),
      InvalidSignature(
        bip340Pub.bytes,
        Hash.zero.bytes,
        randomBIP340SchnorrSignature.bytes
      )
    )
  }

  it should "test convert pubkey to address" in new ContractFixture {
    val (_, publicKey, _) = genesisKeys(chainIndex.from.value)
    def main() =
      s"""
         |@using(preapprovedAssets = false)
         |TxScript Main {
         |  let caller = callerAddress!()
         |  let address = byteVecToAddress!(#00 ++ blake2b!(#${publicKey.toHexString}))
         |  assert!(address == caller, 0)
         |}
         |""".stripMargin
    testSimpleScript(main())
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
    failSimpleScript(main(block.timestamp, Duration.unsafe(1), 1), InvalidTxInputIndex(1))

    val absoluteLockTimeScript = Compiler
      .compileTxScript(main(TimeStamp.now() + Duration.ofMinutesUnsafe(1), Duration.unsafe(1), 0))
      .rightValue
    intercept[AssertionError](
      simpleScript(blockFlow, chainIndex, absoluteLockTimeScript)
    ).getMessage.startsWith(
      s"Right(TxScriptExeFailed(Absolute lock time verification failed"
    )

    val relativeLockTimeScript = Compiler
      .compileTxScript(main(block.timestamp, Duration.ofMinutesUnsafe(1), 0))
      .rightValue
    intercept[AssertionError](
      simpleScript(blockFlow, chainIndex, relativeLockTimeScript)
    ).getMessage.startsWith(
      s"Right(TxScriptExeFailed(Relative lock time verification failed"
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

  trait VerifyToStringFixture extends ContractFixture {
    def toHex(string: String) = {
      Hex.toHexString(ByteString(string.getBytes(StandardCharsets.US_ASCII)))
    }

    def test(statements: Seq[String]) = {
      testSimpleScript(
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  ${statements.mkString("\n")}
           |}
           |""".stripMargin
      )
    }
  }

  it should "test u256 to string" in new VerifyToStringFixture {
    def check(input: U256, expected: String): String = {
      s"assert!(u256ToString!($input) == #$expected, 0)"
    }
    val statements =
      Gen.listOfN(10, u256Gen).sample.get.map(number => check(number, toHex(number.toString()))) ++
        Seq(check(0, "30"), check(1, "31"))
    test(statements)
  }

  it should "test i256 to string" in new VerifyToStringFixture {
    def check(input: I256, expected: String): String = {
      s"assert!(i256ToString!(${input}i) == #$expected, 0)"
    }
    val statements =
      Gen.listOfN(10, i256Gen).sample.get.map(number => check(number, toHex(number.toString()))) ++
        Seq(
          check(I256.unsafe(0), "30"),
          check(I256.unsafe(1), "31"),
          check(I256.unsafe(-1), "2d31")
        )
    test(statements)
  }

  it should "test bool to string" in new VerifyToStringFixture {
    val statements = Seq(
      s"assert!(boolToString!(true) == #${toHex("true")}, 0)",
      s"assert!(boolToString!(false) == #${toHex("false")}, 0)"
    )
    test(statements)
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
        initialImmState =
          AVector[Val](Val.Address(genesisAddress.lockupScript), Val.U256(U256.unsafe(1000000))),
        initialMutState = AVector.empty,
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
        initialMutState = AVector.empty
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
      AVector[Val](Val.ByteVec.from(tokenId.value)),
      AVector[Val](Val.U256(U256.Zero), Val.U256(U256.Zero)),
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

    checkSwapBalance(minimalAlphInContract, 0, 5, 3)

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
    checkSwapBalance(minimalAlphInContract + 10, 100, 6 /* 1 more coinbase output */, 3)

    callTxScript(s"""
                    |TxScript Main {
                    |  let swap = Swap(#${swapContractId.toHexString})
                    |  swap.swapToken{@$genesisAddress -> ALPH: 10}(@${genesisAddress.toBase58}, 10)
                    |}
                    |
                    |${AMMContract.swapContract}
                    |""".stripMargin)
    checkSwapBalance(minimalAlphInContract + 20, 50, 7 /* 1 more coinbase output */, 3)

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
    checkSwapBalance(minimalAlphInContract + 10, 100, 8 /* 1 more coinbase output */, 3)
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
      contractState.mutFields is AVector[Val](Val.U256(U256.unsafe(expected)))
    }
  }

  it should "execute tx in random order" in new TxExecutionOrderFixture {
    override val configValues = Map(
      ("alephium.network.leman-hard-fork-timestamp", TimeStamp.now().plusHoursUnsafe(1).millis),
      ("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis)
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
        usePayToContractOnly = false,
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

      checkState(
        blockFlow,
        chainIndex,
        contractId,
        AVector.empty,
        initialState,
        contractOutputRef,
        2,
        2
      )
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
    override val configValues =
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
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
      state.mutFields is AVector[Val](Val.U256(x))
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
      val (contractId, contractOutputRef) =
        createContract(contract, AVector.empty, AVector(Val.U256(0)))
      val contractIdHex = contractId.toHexString
      checkContractState(contractIdHex, contract, contractOutputRef, true)

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
      contractState.mutFields is AVector[Val](Val.U256(3))
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

    lazy val contract        = Compiler.compileContract(contractRaw).rightValue
    lazy val initialImmState = AVector[Val](Val.U256.unsafe(10))
    lazy val initialMutState = AVector.empty[Val]
    lazy val chainIndex      = ChainIndex.unsafe(0, 0)
    lazy val fromLockup      = getGenesisLockupScript(chainIndex)
    lazy val genesisAddress  = Address.Asset(fromLockup)
    lazy val contractCreationScript =
      contractCreation(contract, initialImmState, initialMutState, fromLockup, ALPH.alph(1))
    lazy val createContractBlock =
      payableCall(blockFlow, chainIndex, contractCreationScript)
    lazy val contractOutputRef =
      TxOutputRef.unsafe(createContractBlock.transactions.head, 0).asInstanceOf[ContractOutputRef]
    lazy val contractId =
      ContractId.from(createContractBlock.transactions.head.id, 0, chainIndex.from)

    lazy val createContractEventId  = vm.createContractEventId(chainIndex.from.value)
    lazy val destroyContractEventId = vm.destroyContractEventId(chainIndex.from.value)

    addAndCheck(blockFlow, createContractBlock, 1)
    checkState(
      blockFlow,
      chainIndex,
      contractId,
      initialImmState,
      initialMutState,
      contractOutputRef
    )

    val callingScript = Compiler.compileTxScript(callingScriptRaw, 1).rightValue
    val callingBlock  = simpleScript(blockFlow, chainIndex, callingScript)
    addAndCheck(blockFlow, callingBlock, 2)
  }

  trait EventFixtureWithContract extends EventFixture {
    override lazy val initialImmState = AVector.empty[Val]
    override lazy val initialMutState = AVector[Val](Val.U256.unsafe(10))
    override def contractRaw: String =
      s"""
         |Contract Foo(mut result: U256) {
         |
         |  event Adding(a: U256, b: U256)
         |  event Added()
         |  event ContractEvent(foo: Foo)
         |
         |  @using(updateFields = true)
         |  pub fn add(a: U256) -> (U256) {
         |    emit Adding(a, result)
         |    result = result + a
         |    emit Added()
         |    emit ContractEvent(Foo(selfContractId!()))
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
      logStates.states.length is 3

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

      val contractLogState = logStates.states(2)
      contractLogState.txId is block.nonCoinbase.head.id
      contractLogState.index is 2.toByte
      contractLogState.fields is AVector[Val](Val.ByteVec(contractId.bytes))
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
      createContractLogState.fields.length is 3
      createContractLogState.fields(0) is Val.Address(LockupScript.p2c(contractId))
      createContractLogState.fields(1) is Val.ByteVec(ByteString.empty)
      createContractLogState.fields(2) is Val.ByteVec(ByteString.empty)
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

      newCounter is 3

      AVector(1, 2, 3, 100).foreach { count =>
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

  it should "Log contract and subcontract ids when subcontract is created" in new SubContractFixture {
    val subContractPath1 = Hex.toHexString(serialize("nft-01"))
    val (contractId, subContractId) = verify(
      s"createSubContract!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath1, #$subContractByteCode, #00, #00)",
      subContractPath = "nft-01",
      numOfAssets = 2,
      numOfContracts = 2
    )

    val logStatesOpt = getLogStates(blockFlow, createContractEventId(chainIndex.from.value), 1)
    val logStates    = logStatesOpt.value

    val fields = logStates.states(0).fields

    fields.length is 3
    fields(0) is Val.Address(LockupScript.p2c(subContractId))
    fields(1) is Val.Address(LockupScript.p2c(contractId))
    fields(2) is Val.ByteVec(ByteString.empty)
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
         |Contract Foo(result: U256) {
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
      logStates2.states.length is 3

      val addingLogState = logStates2.states(0)
      addingLogState.txId is secondCallingBlock.nonCoinbase.head.id
      addingLogState.index is 0.toByte
      addingLogState.fields.length is 2
      addingLogState.fields(0) is Val.U256(U256.unsafe(4))
      addingLogState.fields(1) is Val.U256(U256.unsafe(14))
    }
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
         |    subContractId = copyCreateContract!{callerAddress!() -> ALPH: $minimalAlphInContract}(selfContractId!(), #00, #010300)
         |    emit Create(subContractId)
         |  }
         |}
         |""".stripMargin
    val contractId =
      createContractAndCheckState(
        contract,
        2,
        2,
        AVector.empty,
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
    worldState.getContractState(contractId).rightValue.mutFields is AVector[Val](
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
    val subContract             = Compiler.compileContract(subContractRaw).rightValue
    val subContractByteCode     = Hex.toHexString(serialize(subContract))

    // scalastyle:off method.length
    def verify(
        createContractStmt: String,
        subContractPath: String,
        numOfAssets: Int,
        numOfContracts: Int
    ): (ContractId, ContractId) = {
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
           |    let subContractIdCalculatedTest = subContractIdOf!(selfContract!(), path)
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
          initialMutState = AVector(Val.ByteVec(ByteString.empty))
        )._1

      val createSubContractRaw: String =
        s"""
           |TxScript Main {
           |  Foo(#${contractId.toHexString}).createSubContract{callerAddress!() -> ALPH: 1 alph}()
           |}
           |$contractRaw
           |""".stripMargin

      callTxScript(createSubContractRaw)

      val subContractId      = contractId.subContractId(serialize(subContractPath), chainIndex.from)
      val subContractAddress = Address.contract(subContractId)
      val worldState         = blockFlow.getBestCachedWorldState(chainIndex.from).rightValue
      worldState.getContractState(contractId).rightValue.mutFields is AVector[Val](
        Val.ByteVec(subContractId.bytes)
      )

      intercept[AssertionError](callTxScript(createSubContractRaw)).getMessage.startsWith(
        s"Right(TxScriptExeFailed(ContractAlreadyExists(${subContractAddress}))"
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

      (contractId, subContractId)
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

      val contractId =
        createContract(contractRaw, AVector.empty, AVector(Val.ByteVec(ByteString.empty)))._1

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
    {
      info("create sub-contract without token")
      val subContractPath1 = Hex.toHexString(serialize("nft-01"))
      verify(
        s"createSubContract!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath1, #$subContractByteCode, #00, #00)",
        subContractPath = "nft-01",
        numOfAssets = 2,
        numOfContracts = 2
      )
    }

    {
      info("create sub-contract with token")
      val subContractPath2 = Hex.toHexString(serialize("nft-02"))
      val (_, subContractId) = verify(
        s"createSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath2, #$subContractByteCode, #00, #00, 10)",
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
      val (_, subContractId) = verify(
        s"createSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath3, #$subContractByteCode, #00, #00, 10, @${genesisAddress.toBase58})",
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
        s"createSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath4, #$subContractByteCode, #00, #00, 10, @${contractAddress.toBase58})",
        InvalidAssetAddress(contractAddress)
      )
    }
  }

  it should "check copyCreateSubContract and copyCreateSubContractWithToken" in new SubContractFixture {
    val subContractId =
      createContractAndCheckState(subContractRaw, 2, 2, initialMutState = AVector.empty)._1

    {
      info("copy create sub-contract without token")
      val subContractPath1 = Hex.toHexString(serialize("nft-01"))
      verify(
        s"copyCreateSubContract!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath1, #${subContractId.toHexString}, #00, #00)",
        subContractPath = "nft-01",
        numOfAssets = 3,
        numOfContracts = 3
      )
    }

    {
      info("copy create sub-contract with token")
      val subContractPath2 = Hex.toHexString(serialize("nft-02"))
      val (_, contractId) = verify(
        s"copyCreateSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath2, #${subContractId.toHexString}, #00, #00, 10)",
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
      val (_, contractId) = verify(
        s"copyCreateSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath3, #${subContractId.toHexString}, #00, #00, 10, @${genesisAddress.toBase58})",
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
        s"copyCreateSubContractWithToken!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath4, #${subContractId.toHexString}, #00, #00, 10, @${contractAddress.toBase58})",
        InvalidAssetAddress(contractAddress)
      )
    }
  }

  it should "check subContractIdInParentGroup" in new SubContractFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val subContractPath = "00"
    val parentContract =
      s"""
         |Contract Parent() {
         |  @using(preapprovedAssets = true)
         |  pub fn createSubContract() -> () {
         |    createSubContract!{callerAddress!() -> ALPH: 1 alph}(#$subContractPath, #$subContractByteCode, #00, #00)
         |  }
         |}
         |""".stripMargin
    val contractId    = createContract(parentContract, chainIndex = ChainIndex.unsafe(0, 0))._1
    val contractIdHex = contractId.toHexString

    val createSubContractScript =
      s"""
         |TxScript Main() {
         |  Parent(#$contractIdHex).createSubContract{callerAddress!() -> ALPH: 1 alph}()
         |}
         |$parentContract
         |""".stripMargin
    callTxScript(createSubContractScript, chainIndex = ChainIndex.unsafe(0, 0))
    val subContractId = contractId.subContractId(Hex.unsafe(subContractPath), contractId.groupIndex)
    val subContractIdHex = subContractId.toHexString

    val script =
      s"""
         |TxScript Main() {
         |  let parent = Parent(#$contractIdHex)
         |  assert!(subContractIdOf!(parent, #$subContractPath) != #$subContractIdHex, 0)
         |  assert!(subContractIdInParentGroup!(parent, #$subContractPath) == #$subContractIdHex, 1)
         |}
         |$parentContract
         |""".stripMargin
    callTxScript(script, chainIndex = ChainIndex.unsafe(1, 1))
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
    lazy val fooIdHex = createContract(foo)._1.toHexString

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
    failCallTxScript(script, InvalidExternalMethodArgLength(0, 2))
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
    failCallTxScript(script, InvalidExternalMethodReturnLength(0, 3))
  }

  trait CreateContractFixture extends ContractFixture {
    def useAssets = true
    lazy val contract: String =
      s"""
         |Contract Foo(mut n: U256) {
         |  @using(preapprovedAssets = true, updateFields = true)
         |  pub fn foo() -> () {
         |    let subContractId = copyCreateSubContract!{
         |      callerAddress!() -> ALPH: 1 alph
         |    }(#00, selfContractId!(), #00, #010201)
         |    let subContract = Foo(subContractId)
         |    subContract.bar()
         |  }
         |  @using(${if (useAssets) "assetsInContract = true, " else ""}updateFields = true)
         |  pub fn bar() -> () {
         |    ${if (useAssets) "transferTokenFromSelf!(selfAddress!(), ALPH, 1 alph)" else ""}
         |    n = n + 1
         |  }
         |}
         |""".stripMargin
    lazy val contractId =
      createContractAndCheckState(
        contract,
        2,
        2,
        initialMutState = AVector(Val.U256(0))
      )._1

    lazy val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${contractId.toHexString}).foo{callerAddress!() -> ALPH: 1 alph}()
         |}
         |
         |$contract
         |""".stripMargin
    lazy val script = Compiler.compileTxScript(main).rightValue
  }

  it should "not load contract just after creation before Rhone upgrade" in new CreateContractFixture {
    override val configValues = Map(
      ("alephium.network.ghost-hard-fork-timestamp", TimeStamp.now().plusHoursUnsafe(1).millis)
    )
    config.network.getHardFork(TimeStamp.now()) is HardFork.Leman

    val errorMessage =
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
    errorMessage.contains(s"Right(TxScriptExeFailed(ContractLoadDisallowed") is true
  }

  it should "not load contract assets just after creation from Rhone upgrade" in new CreateContractFixture {
    config.network.getHardFork(TimeStamp.now()) is HardFork.Ghost

    val errorMessage =
      intercept[AssertionError](payableCall(blockFlow, chainIndex, script)).getMessage
    errorMessage.contains(s"Right(TxScriptExeFailed(ContractAssetAlreadyFlushed)") is true
  }

  it should "load contract fields just after creation from Rhone upgrade" in new CreateContractFixture {
    override def useAssets: Boolean = false

    config.network.getHardFork(TimeStamp.now()) is HardFork.Ghost

    val block = payableCall(blockFlow, chainIndex, script)
    addAndCheck(blockFlow, block)

    val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
    val contractState = worldState.getContractState(contractId).rightValue
    contractState.immFields.isEmpty is true
    contractState.mutFields is AVector[Val](Val.U256(0))

    val subContractId    = contractId.subContractId(Hex.unsafe("00"), chainIndex.from)
    val subContractState = worldState.getContractState(subContractId).fold(throw _, identity)
    subContractState.immFields.isEmpty is true
    subContractState.mutFields is AVector[Val](Val.U256(2)) // The field was updated from 1 to 2
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
    val fooId = createContract(s"$foo\n$bar", AVector.empty, AVector.empty)._1
    val barId = createContract(s"$bar\n$foo", AVector.empty, AVector.empty)._1

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
    errorMessage is "Right(TxScriptExeFailed(`destroySelf` function should not be called from the same contract))"
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
    val fooId = createContract(foo)._1
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

  it should "test Contract.encodeFields" in new ContractFixture {
    def test(stdAnnotation: String, fields: String, immFields: String, mutFields: String) = {
      val foo = s"""
                   |Contract Bar(a: U256, @unused mut b: I256, @unused c: ByteVec) implements Foo {
                   |  @using(checkExternalCaller = false)
                   |  pub fn foo() -> () {
                   |    let (immFields, mutFields) = Bar.encodeFields!($fields)
                   |    assert!(immFields == #$immFields, 0)
                   |    assert!(mutFields == #$mutFields, 0)
                   |  }
                   |}
                   |
                   |$stdAnnotation
                   |Interface Foo {
                   |  pub fn foo() -> ()
                   |}
                   |""".stripMargin
      val initialImmFields = AVector[Val](Val.U256(0), Val.ByteVec(Hex.unsafe("00")))
      val fooId = createContract(
        foo,
        initialImmState = if (stdAnnotation == "") {
          initialImmFields
        } else {
          initialImmFields :+ Val.ByteVec(Hex.unsafe("414c50480001"))
        },
        initialMutState = AVector(Val.I256(I256.unsafe(-2)))
      )._1
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

    intercept[Throwable](test("", "1, #11, 2i", "", "")).getMessage is
      "org.alephium.ralph.error.CompilerError$Default: Invalid args type \"List(U256, ByteVec, I256)\" for builtin func encodeFields"
    test("", "1, 2i, #11", "020201030111", "010102")
    test("@std(id = #0001)", "1, 2i, #11", "0302010301110306414c50480001", "010102")
  }

  it should "encode array type contract fields" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo(@unused a: U256, @unused mut b: [U256; 2], @unused mut c: U256, @unused d: [U256; 2]) {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin

    val fooContract = Compiler.compileContract(foo).rightValue
    val fooBytecode = Hex.toHexString(serialize(fooContract))

    val script0 =
      s"""
         |TxScript Deploy() {
         |  let (encodedImmFields, encodedMutFields) = Foo.encodeFields!(0, [1, 2], 3, [4, 5])
         |  createContract!{@$genesisAddress -> ALPH: $minimalAlphInContract}(#$fooBytecode, encodedImmFields, encodedMutFields)
         |}
         |$foo
         |""".stripMargin

    deployAndCheckContractState(
      script0,
      AVector.from(Seq(0, 4, 5)).map(v => Val.U256(U256.unsafe(v))),
      AVector.from(Seq(1, 2, 3)).map(v => Val.U256(U256.unsafe(v)))
    )
  }

  trait SelfContractFixture extends ContractFixture {
    def foo(selfContractStr: String): String

    def test(selfContractStr: String) = {
      val fooStr = foo(selfContractStr)
      val fooId = createContract(
        fooStr,
        initialImmState = AVector.empty,
        initialMutState = AVector.empty
      )._1

      val main: String =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  Foo(#${fooId.toHexString}).selfHello()
           |}
           |
           |$fooStr
           |""".stripMargin
      testSimpleScript(main)
    }
  }

  it should "test selfContract for contract" in new SelfContractFixture {
    override def foo(selfContractStr: String) = {
      s"""
         |Contract Foo() {
         |  pub fn hello() -> () {
         |    emit Debug(`Hello`)
         |  }
         |
         |  pub fn selfHello() -> () {
         |    let foo = $selfContractStr
         |    foo.hello()
         |  }
         |}
         |""".stripMargin
    }

    test("Foo(selfContractId!())")
    test("selfContract!()")
  }

  it should "test selfContract for abstract contract" in new SelfContractFixture {
    override def foo(selfContractStr: String) = {
      s"""
         |Contract Foo() extends AbstractFoo() {
         |  pub fn hello() -> () {
         |    emit Debug(`Hello`)
         |  }
         |}
         |Abstract Contract AbstractFoo() {
         |  pub fn hello() ->()
         |
         |  pub fn selfHello() -> () {
         |    let foo = $selfContractStr
         |    foo.hello()
         |  }
         |}
         |""".stripMargin
    }
    intercept[Throwable](test("AbstractFoo(selfContractId!())")).getMessage is
      "org.alephium.ralph.error.CompilerError$Default: AbstractFoo is not instantiable"
    test("Foo(selfContractId!())")
    test("selfContract!()")
  }

  it should "test selfContract for interface" in new SelfContractFixture {
    override def foo(selfContractStr: String) = {
      s"""
         |Contract Foo() implements InterfaceFoo {
         |  pub fn hello() -> () {
         |    emit Debug(`Hello`)
         |  }
         |
         |  pub fn selfHello() -> () {
         |    let foo = $selfContractStr
         |    foo.hello()
         |  }
         |}
         |
         |Interface InterfaceFoo {
         |  pub fn hello() ->()
         |  pub fn selfHello() -> ()
         |}
         |""".stripMargin
    }
    test("InterfaceFoo(selfContractId!())")
    test("Foo(selfContractId!())")
    test("selfContract!()")
  }

  it should "test selfContract for multi-level inheritance" in new SelfContractFixture {
    override def foo(selfContractStr: String) = {
      s"""
         |Contract Foo() extends AbstractFooParent() {
         |  pub fn hello() -> () {
         |    emit Debug(`Hello`)
         |  }
         |}
         |
         |Abstract Contract AbstractFooParent() extends AbstractFooGrandParent() {
         |}
         |
         |Abstract Contract AbstractFooGrandParent() {
         |  pub fn hello() ->()
         |
         |  pub fn selfHello() -> () {
         |    let foo = $selfContractStr
         |    foo.hello()
         |  }
         |}
         |""".stripMargin
    }
    intercept[Throwable](test("AbstractFooGrandParent(selfContractId!())")).getMessage is
      "org.alephium.ralph.error.CompilerError$Default: AbstractFooGrandParent is not instantiable"
    test("Foo(selfContractId!())")
    test("selfContract!()")
  }

  it should "test selfContract for multiple parents" in new SelfContractFixture {
    override def foo(selfContractStr: String) = {
      s"""
         |Contract Foo() extends FooParent1(), FooParent2() {
         |  pub fn hello() -> () {
         |    emit Debug(`Hello 1`)
         |  }
         |
         |  pub fn hello2() -> () {
         |    emit Debug(`Hello 2`)
         |  }
         |}
         |
         |Abstract Contract FooParent1() {
         |  pub fn hello2() ->()
         |}
         |
         |Abstract Contract FooParent2() {
         |  pub fn hello() ->()
         |
         |  pub fn selfHello() -> () {
         |    let foo = $selfContractStr
         |    foo.hello()
         |  }
         |}
         |""".stripMargin
    }
    intercept[Throwable](test("FooParent2(selfContractId!())")).getMessage is
      "org.alephium.ralph.error.CompilerError$Default: FooParent2 is not instantiable"
    test("Foo(selfContractId!())")
    test("selfContract!()")
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
    val fooId      = createContract(foo)._1
    val fooAddress = Address.contract(fooId)

    val main: String =
      s"""
         |TxScript Main {
         |  Foo(#${fooId.toHexString}).foo()
         |  transferToken!(callerAddress!(), @${fooAddress.toBase58}, ALPH, 1 alph)
         |}
         |
         |$foo
         |""".stripMargin
    failCallTxScript(main, PayToContractAddressNotInCallerTrace(fooAddress))
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

      val contractId = createContract(contract)._1

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

    val contractId = createContract(contract)._1

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
    val barId = createContract(bar)._1

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

    val fooContract = Compiler.compileContract(foo).rightValue
    val fooByteCode = Hex.toHexString(serialize(fooContract))

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
           |    let contractId = createContract!{@$genesisAddress -> ALPH: $minimalAlphInContract}(#$fooByteCode, #00, #00)
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
    intercept[AssertionError](callTxScript(createFooContract(true))).getMessage.startsWith(
      s"Right(TxScriptExeFailed(Pay to contract address"
    )
  }

  it should "not transfer assets to arbitrary contract" in new ContractFixture {
    val randomContract = Address.contract(ContractId.random)

    {
      info("Transfer to random contract address in TxScript")
      val script =
        s"""
           |TxScript Main {
           |  let caller = callerAddress!()
           |  transferToken!(caller, @${randomContract.toBase58}, ALPH, 0.01 alph)
           |}
           |""".stripMargin

      failCallTxScript(script, PayToContractAddressNotInCallerTrace(randomContract))
    }

    {
      info("Transfer to random contract address in Contract")

      val foo: String =
        s"""
           |Contract Foo() {
           |  @using(assetsInContract = true)
           |  pub fn foo() -> () {
           |    transferTokenFromSelf!(@${randomContract.toBase58}, ALPH, 0.01 alph)
           |  }
           |}
           |""".stripMargin
      val fooId      = createContract(foo, initialAttoAlphAmount = ALPH.alph(2))._1
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
      failCallTxScript(script, PayToContractAddressNotInCallerTrace(fooAddress))
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
      val fooId =
        createContract(foo, initialAttoAlphAmount = ALPH.alph(2))._1

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
        val barId =
          createContract(bar, initialFields, AVector.empty, initialAttoAlphAmount = ALPH.alph(2))._1
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
    val fooId = createContract(foo, initialAttoAlphAmount = ALPH.alph(2))._1

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
    val fooId = createContract(foo)._1

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
      s"Right(TxScriptExeFailed(Assertion Failed in Contract @ ${Address.contract(fooId).toBase58}, Error Code: 0))"
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
    val fooId = createContract(foo, AVector(Val.U256(123)), AVector.empty)._1

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
    val barId = createContract(bar)._1

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
    val (fooId, fooOutputRef) = createContract(foo)

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
    override lazy val initialImmState: AVector[Val] = AVector.empty
    override lazy val initialMutState: AVector[Val] = AVector(Val.ByteVec.fromString("Alephium"))
    override def contractRaw: String =
      s"""
         |Contract Foo(mut name: ByteVec) {
         |  pub fn foo() -> () {
         |    emit Debug(`Hello, $${name}!`)
         |  }
         |
         |  pub fn setName() -> () {
         |    name = #
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
    event.fields is AVector[Val](Val.ByteVec(ByteString.fromString("Hello, 416c65706869756d!")))
  }

  it should "test tokenId/contractId/contractAddress built-in function" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {}
         |}
         |""".stripMargin

    val tokenIssuanceInfo = TokenIssuance.Info(Val.U256(U256.unsafe(1024)), Some(genesisLockup))
    val (fooId, _)        = createContract(foo, tokenIssuanceInfo = Some(tokenIssuanceInfo))
    val fooAddress        = Address.contract(fooId)

    def barCode(assertStmt: String): String = {
      s"""
         |Contract Bar() {
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn bar(foo: Foo, caller: Address) -> () {
         |    assert!($assertStmt, 1)
         |    transferTokenToSelf!(caller, tokenId!(foo), 1)
         |  }
         |}
         |$foo
         |""".stripMargin
    }

    def verifyInvalidArgumentType(func: String, assertValue: String) = {
      val code = barCode(s"$func!(caller) == $assertValue")
      intercept[Throwable](createContract(code)).getMessage is
        s"org.alephium.ralph.error.CompilerError$$Default: Invalid args type \"List(Address)\" for builtin func $func, expected \"List(Contract)\""
    }

    def verifyInvalidNumberOfArguments(func: String, assertValue: String) = {
      val code = barCode(s"$func!(1, caller) == $assertValue")
      intercept[Throwable](createContract(code)).getMessage is
        s"org.alephium.ralph.error.CompilerError$$Default: Invalid args type \"List(U256, Address)\" for builtin func $func, expected \"List(Contract)\""
    }

    def verifyTransferToken(func: String, assertValue: String) = {
      val bar        = barCode(s"$func!(foo) == $assertValue")
      val (barId, _) = createContract(bar)
      val script =
        s"""
           |TxScript Main {
           |  let foo = Foo(#${fooId.toHexString})
           |  Bar(#${barId.toHexString}).bar{@$genesisAddress -> tokenId!(foo): 1}(foo, @$genesisAddress)
           |}
           |$bar
           |""".stripMargin

      callTxScript(script)
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val barState   = worldState.getContractState(barId).rightValue
      val barContractOutput = worldState.getContractOutput(barState.contractOutputRef).rightValue
      barContractOutput.tokens is AVector((TokenId.from(fooId), U256.One))
    }

    {
      info("Invalid argument type")
      verifyInvalidArgumentType("tokenId", s"#${fooId.toHexString}")
      verifyInvalidArgumentType("contractId", s"#${fooId.toHexString}")
      verifyInvalidArgumentType("contractAddress", s"@${fooAddress.toBase58}")
    }

    {
      info("Invalid number of arguments")
      verifyInvalidNumberOfArguments("tokenId", s"#${fooId.toHexString}")
      verifyInvalidNumberOfArguments("contractId", s"#${fooId.toHexString}")
      verifyInvalidNumberOfArguments("contractAddress", s"@${fooAddress.toBase58}")
    }

    {
      info("Transfer token successfully")
      verifyTransferToken("tokenId", s"#${fooId.toHexString}")
      verifyTransferToken("contractId", s"#${fooId.toHexString}")
      verifyTransferToken("contractAddress", s"@${fooAddress.toBase58}")
    }
  }

  it should "test contract asset only function" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn foo() -> () {
         |    assert!(tokenRemaining!(selfAddress!(), ALPH) == 0.1 alph, 0)
         |  }
         |}
         |""".stripMargin
    val contractId = createContract(foo)._1

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

  it should "create contract with std id" in new ContractFixture {
    def code(enabled: Boolean): String =
      s"""
         |@std(enabled = $enabled)
         |Contract Bar(@unused a: U256) implements Foo {
         |  pub fn foo() -> () {}
         |}
         |
         |@std(id = #0001)
         |Interface Foo {
         |  pub fn foo() -> ()
         |}
         |""".stripMargin

    {
      info("The std id of the contract is enabled")
      val contractCode = code(true)
      val fields       = AVector[Val](Val.U256(0))
      intercept[AssertionError](createContract(contractCode, fields)).getMessage is
        "Right(TxScriptExeFailed(InvalidFieldLength))"

      val stdId      = Val.ByteVec(Hex.unsafe("414c50480001"))
      val contractId = createContract(contractCode, fields :+ stdId)._1
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val contractState = worldState.getContractState(contractId).rightValue
      contractState.immFields is AVector[Val](Val.U256(0), stdId)
      contractState.mutFields is AVector.empty[Val]

      val logStatesOpt = getLogStates(blockFlow, createContractEventId(chainIndex.from.value), 0)
      val logStates    = logStatesOpt.value

      val eventFields = logStates.states(0).fields

      eventFields.length is 3
      eventFields(0) is Val.Address(LockupScript.p2c(contractId))
      eventFields(1) is Val.ByteVec(ByteString.empty)
      eventFields(2) is Val.ByteVec(ByteString(0, 1))
    }

    {
      info("The std id of the contract is disabled")
      val contractCode = code(false)
      val fields       = AVector[Val](Val.U256(0))
      val stdId        = Val.ByteVec(Hex.unsafe("414c50480001"))
      intercept[AssertionError](createContract(contractCode, fields :+ stdId)).getMessage is
        "Right(TxScriptExeFailed(InvalidFieldLength))"

      val contractId = createContract(contractCode, fields)._1
      val worldState = blockFlow.getBestPersistedWorldState(chainIndex.from).fold(throw _, identity)
      val contractState = worldState.getContractState(contractId).rightValue
      contractState.immFields is AVector[Val](Val.U256(0))
      contractState.mutFields is AVector.empty[Val]

      val logStatesOpt = getLogStates(blockFlow, createContractEventId(chainIndex.from.value), 1)
      val logStates    = logStatesOpt.value

      val eventFields = logStates.states(0).fields

      eventFields.length is 3
      eventFields(0) is Val.Address(LockupScript.p2c(contractId))
      eventFields(1) is Val.ByteVec(ByteString.empty)
      eventFields(2) is Val.ByteVec(ByteString.empty)
    }
  }

  it should "always return ContractAssetUnloaded when transferring assetes to a contract without assetsInContract set to true" in new ContractFixture {
    def test(amount: U256, useContractAsset: Boolean) = {
      val code: String =
        s"""
           |Contract Foo() {
           |  @using(assetsInContract=${useContractAsset}, preapprovedAssets = true)
           |  pub fn payMe(amount: U256) -> () {
           |    transferTokenToSelf!(callerAddress!(), ALPH, amount)
           |  }
           |}
           |""".stripMargin

      val contractId = createContract(code)._1

      val script: String =
        s"""|
            |@using(preapprovedAssets = true)
            |TxScript Bar {
            |  let foo = Foo(#${contractId.toHexString})
            |  foo.payMe{callerAddress!() -> ALPH: $amount}($amount)
            |}
            |
            |${code}
            |""".stripMargin

      if (useContractAsset) {
        callTxScript(script)
      } else {
        failCallTxScript(script, ContractAssetUnloaded(Address.contract(contractId)))
      }
    }

    test(ALPH.oneNanoAlph, true)
    test(ALPH.oneNanoAlph, false)
    test(ALPH.oneAlph, true)
    test(ALPH.oneAlph, false)
  }

  it should "return LowerThanContractMinimalBalance if contract balance falls below the minimal deposit" in new ContractFixture {
    def test(amount: U256) = {
      val code: String =
        s"""
           |Contract Foo() {
           |  @using(assetsInContract=true, preapprovedAssets = true)
           |  pub fn payMe(amount: U256) -> () {
           |    transferTokenFromSelf!(callerAddress!(), ALPH, amount)
           |  }
           |}
           |""".stripMargin

      val contractInitialAlphAmount = ALPH.oneAlph * 2
      val contractId = createContract(code, initialAttoAlphAmount = contractInitialAlphAmount)._1

      val script: String =
        s"""|
            |@using(preapprovedAssets = true)
            |TxScript Bar {
            |  let foo = Foo(#${contractId.toHexString})
            |  foo.payMe{callerAddress!() -> ALPH: $amount}($amount)
            |}
            |
            |${code}
            |""".stripMargin

      if (amount > ALPH.oneAlph) {
        failCallTxScript(
          script,
          LowerThanContractMinimalBalance(
            Address.contract(contractId),
            contractInitialAlphAmount - amount
          )
        )
      } else {
        callTxScript(script)
      }
    }

    test(ALPH.oneNanoAlph)
    test(minimalAlphInContract - 1)
    test(minimalAlphInContract)
    test(minimalAlphInContract + 1)
  }

  it should "call the correct contract method based on the interface method index" in new ContractFixture {
    val fooV0 =
      s"""
         |Interface FooV0 {
         |  pub fn f0() -> U256
         |  pub fn f1() -> U256
         |}
         |""".stripMargin
    val foo0 =
      s"""
         |Contract Foo0() implements FooV0 {
         |  pub fn f0() -> U256 { return 0 }
         |  pub fn f1() -> U256 { return 1 }
         |}
         |$fooV0
         |""".stripMargin
    val (foo0ContractId, _) = createContract(foo0)

    val foo =
      s"""
         |Interface Foo {
         |  @using(methodIndex = 1)
         |  pub fn f1() -> U256
         |}
         |""".stripMargin
    val fooV1 =
      s"""
         |Interface FooV1 extends Foo {
         |  pub fn f2() -> U256
         |}
         |$foo
         |""".stripMargin
    val foo1 =
      s"""
         |Contract Foo1() implements FooV1 {
         |  pub fn f1() -> U256 { return 1 }
         |  pub fn f2() -> U256 { return 2 }
         |}
         |$fooV1
         |""".stripMargin
    val (foo1ContractId, _) = createContract(foo1)
    val script =
      s"""
         |TxScript Main {
         |  assert!(Foo(#${foo0ContractId.toHexString}).f1() == 1, 0)
         |  assert!(Foo(#${foo1ContractId.toHexString}).f1() == 1, 0)
         |
         |  assert!(FooV0(#${foo0ContractId.toHexString}).f0() == 0, 0)
         |  assert!(FooV0(#${foo0ContractId.toHexString}).f1() == 1, 0)
         |
         |  assert!(FooV1(#${foo1ContractId.toHexString}).f1() == 1, 0)
         |  assert!(FooV1(#${foo1ContractId.toHexString}).f2() == 2, 0)
         |}
         |$fooV0
         |$fooV1
         |""".stripMargin
    callTxScript(script)
  }

  "Mempool" should "remove invalid transaction" in new ContractFixture {
    val code =
      s"""
         |Contract Foo() {
         |  pub fn foo() -> () {
         |    assert!(false, 0)
         |  }
         |}
         |""".stripMargin

    val contractId = createContract(code)._1

    val scriptStr: String =
      s"""|
          |@using(preapprovedAssets = false)
          |TxScript Bar {
          |  let foo = Foo(#${contractId.toHexString})
          |  foo.foo()
          |}
          |
          |${code}
          |""".stripMargin
    val script = Compiler.compileTxScript(scriptStr).rightValue
    val tx = transferTxs(
      blockFlow,
      chainIndex,
      ALPH.alph(1),
      1,
      Some(script),
      true,
      validation = false
    ).head

    val (_, minerPubKey) = chainIndex.to.generateKey
    val miner            = LockupScript.p2pkh(minerPubKey)
    val emptyTemplate    = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)

    blockFlow.getGrandPool().add(chainIndex, tx.toTemplate, TimeStamp.now())
    blockFlow.getGrandPool().size is 1
    val newTemplate =
      emptyTemplate.copy(transactions = AVector(tx, emptyTemplate.transactions.last))

    // The invalid tx is removed
    blockFlow.validateTemplate(chainIndex, newTemplate, AVector.empty, miner)
    blockFlow.getGrandPool().size is 0
  }

  it should "test encode struct type contract fields" in new ContractFixture {
    val contract =
      s"""
         |struct Foo {x: U256, mut y: U256}
         |struct Bar {mut a: [Foo; 2], b: U256 }
         |Contract C(@unused mut a: U256, @unused b: U256, @unused mut bar: Bar) {
         |  pub fn f() -> () {}
         |}
         |""".stripMargin

    val compiledContract = Compiler.compileContract(contract).rightValue
    val contractBytecode = Hex.toHexString(serialize(compiledContract))
    // put field `b` at first for testing
    val fields = "0, 1, Bar{b:6, a: [Foo{y: 3, x: 2}, Foo{x: 4, y: 5}]}"

    val script =
      s"""
         |TxScript Deploy() {
         |  let (encodedImmFields, encodedMutFields) = C.encodeFields!($fields)
         |  createContract!{@$genesisAddress -> ALPH: $minimalAlphInContract}(
         |    #$contractBytecode,
         |    encodedImmFields,
         |    encodedMutFields
         |  )
         |}
         |$contract
         |""".stripMargin

    deployAndCheckContractState(
      script,
      AVector.from(Seq(1, 2, 4, 6)).map(v => Val.U256(U256.unsafe(v))),
      AVector.from(Seq(0, 3, 5)).map(v => Val.U256(U256.unsafe(v)))
    )
  }

  it should "test maximum method index for CallInternal and CallExternal" in new ContractFixture {
    val maxMethodSize = 0xff + 1
    val fooMethods = (0 until maxMethodSize).map { index =>
      s"""
         |pub fn func$index() -> U256 {
         |  return $index
         |}
         |""".stripMargin
    }
    val foo =
      s"""
         |Contract Foo() {
         |  ${fooMethods.mkString("\n")}
         |}
         |""".stripMargin
    val fooId = createContract(foo)._1

    val barMethods = (0 until maxMethodSize).map { index =>
      s"""
         |pub fn func$index() -> U256 {
         |  return foo.func$index()
         |}
         |""".stripMargin
    }
    val bar =
      s"""
         |Contract Bar(foo: Foo) {
         |  ${barMethods.mkString("\n")}
         |}
         |$foo
         |""".stripMargin
    val barId = createContract(bar, AVector(Val.ByteVec(fooId.bytes)))._1

    (0 until maxMethodSize).foreach { index =>
      val script =
        s"""
           |@using(preapprovedAssets = false)
           |TxScript Main {
           |  assert!(Bar(#${barId.toHexString}).func$index() == $index, 0)
           |}
           |$bar
           |""".stripMargin
      callTxScript(script, chainIndex)
    }
  }

  trait MapFixture extends ContractFixture {
    def mapContract: String
    lazy val mapContractId = createContract(mapContract)._1
    lazy val insert =
      s"""
         |TxScript Insert {
         |  let mapContract = MapContract(#${mapContractId.toHexString})
         |  mapContract.insert{@$genesisAddress -> ALPH: 2 alph}()
         |}
         |$mapContract
         |""".stripMargin

    lazy val checkAndUpdate =
      s"""
         |TxScript CheckAndUpdate {
         |  let mapContract = MapContract(#${mapContractId.toHexString})
         |  mapContract.checkAndUpdate()
         |}
         |$mapContract
         |""".stripMargin

    lazy val remove =
      s"""
         |TxScript Remove {
         |  let mapContract = MapContract(#${mapContractId.toHexString})
         |  mapContract.remove()
         |}
         |$mapContract
         |""".stripMargin

    def mapKeyAndValue: Map[Val, (AVector[Val], AVector[Val])] = Map.empty

    def calcSubPath(key: Val, index: Int = 0) = {
      val prefix =
        ByteString.fromArrayUnsafe(s"__map__${index}__".getBytes(StandardCharsets.US_ASCII))
      prefix ++ key.toByteVec().bytes
    }

    def calcLogMessage(key: Val, prefix: String) = {
      val subPath = calcSubPath(key)
      ByteString.fromString(s"$prefix at map path: ") ++ Val.ByteVec(subPath).toDebugString()
    }

    def calcSubContractId(key: Val, index: Int = 0) = {
      val subPath = calcSubPath(key, index)
      mapContractId.subContractId(subPath, mapContractId.groupIndex)
    }

    lazy val insertAndUpdate =
      s"""
         |TxScript Main {
         |  let mapContract = MapContract(#${mapContractId.toHexString})
         |  mapContract.insert{@$genesisAddress -> ALPH: 2 alph}()
         |  mapContract.checkAndUpdate()
         |}
         |$mapContract
         |""".stripMargin

    def checkSubContractState(
        key: Val,
        immFields: AVector[Val],
        mutFields: AVector[Val],
        mapIndex: Int = 0
    ) = {
      val worldState    = blockFlow.getBestPersistedWorldState(mapContractId.groupIndex).rightValue
      val subContractId = calcSubContractId(key, mapIndex)
      val subContractState = worldState.getContractState(subContractId).rightValue
      subContractState.immFields is (immFields :+ Val.ByteVec(mapContractId.bytes))
      subContractState.mutFields is mutFields
      val contractAsset = worldState.getContractAsset(subContractState.contractOutputRef).rightValue
      contractAsset.amount is minimalAlphInContract
    }

    def subContractNotExist(key: Val, mapIndex: Int = 0) = {
      val subContractId = calcSubContractId(key, mapIndex)
      val worldState    = blockFlow.getBestPersistedWorldState(mapContractId.groupIndex).rightValue
      worldState.contractExists(subContractId).rightValue is false
    }

    def runTest() = {
      val currentCount = getCurrentCount(blockFlow, chainIndex.from, mapContractId).getOrElse(0)
      mapKeyAndValue.foreach { case (key, _) =>
        subContractNotExist(key)
      }
      val balance0 = getAlphBalance(blockFlow, genesisAddress.lockupScript)
      val block0   = callTxScript(insert)
      val balance1 = getAlphBalance(blockFlow, genesisAddress.lockupScript)
      val txFee0   = block0.nonCoinbase.head.gasFeeUnsafe
      (balance0 - txFee0 - minimalAlphInContract.mulUnsafe(mapKeyAndValue.size)) is balance1
      val insertEvent = getLogStates(blockFlow, mapContractId, currentCount).value
      insertEvent.states.length is mapKeyAndValue.size
      mapKeyAndValue.zipWithIndex.foreach { case ((key, (immFields, mutFields)), index) =>
        val logState = insertEvent.states(index)
        logState.index is debugEventIndexInt.toByte
        logState.fields is AVector[Val](Val.ByteVec(calcLogMessage(key, "insert")))
        checkSubContractState(key, immFields, mutFields)
      }
      callTxScript(checkAndUpdate)
      val balance2 = getAlphBalance(blockFlow, genesisAddress.lockupScript)
      val block1   = callTxScript(remove)
      val balance3 = getAlphBalance(blockFlow, genesisAddress.lockupScript)
      val txFee1   = block1.nonCoinbase.head.gasFeeUnsafe
      (balance2 - txFee1 + minimalAlphInContract.mulUnsafe(mapKeyAndValue.size)) is balance3
      val removeEvent = getLogStates(blockFlow, mapContractId, currentCount + 1).value
      removeEvent.states.length is mapKeyAndValue.size
      mapKeyAndValue.zipWithIndex.foreach { case ((key, _), index) =>
        val logState = removeEvent.states(index)
        logState.index is debugEventIndexInt.toByte
        logState.fields is AVector[Val](Val.ByteVec(calcLogMessage(key, "remove")))
        subContractNotExist(key)
      }

      callTxScript(insertAndUpdate)
    }
  }

  it should "test primitive type as map key" in {
    def getDefaultValue(tpe: Val.Type): (String, Val) = {
      tpe match {
        case Val.U256    => (Val.U256.default.v.toString, Val.U256.default)
        case Val.I256    => ("-1i", Val.I256(I256.unsafe(-1)))
        case Val.Bool    => (Val.Bool.default.v.toString, Val.Bool.default)
        case Val.ByteVec => ("#00", Val.ByteVec(Hex.unsafe("00")))
        case Val.Address =>
          (s"@${Val.Address.default.toBase58}", Val.Address.default)
        case _ => throw new RuntimeException("Invalid primitive type")
      }
    }

    def contractCode(address: Address.Asset, keyType: String, keyValue: String): String =
      s"""
         |Contract MapContract() {
         |  mapping[$keyType, U256] map
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    map.insert!(@$address, $keyValue, 1)
         |  }
         |
         |  pub fn checkAndUpdate() -> () {
         |    assert!(map.contains!($keyValue), 0)
         |    assert!(map[$keyValue] == 1, 0)
         |    map[$keyValue] = 2
         |    assert!(map[$keyValue] == 2, 0)
         |  }
         |
         |  pub fn remove() -> () {
         |    map.remove!(@$address, $keyValue)
         |  }
         |}
         |""".stripMargin

    Val.Type.types.foreach { tpe =>
      val defaultValue = getDefaultValue(tpe)
      val fixture = new MapFixture {
        def mapContract: String = contractCode(genesisAddress, tpe.toString, defaultValue._1)
        override def mapKeyAndValue: Map[Val, (AVector[Val], AVector[Val])] =
          Map(defaultValue._2 -> (AVector.empty, AVector(Val.U256(1))))
      }
      fixture.runTest()
    }
  }

  it should "test primitive type as map value" in new MapFixture {
    val mapContract =
      s"""
         |Contract MapContract() {
         |  mapping[U256, ByteVec] map
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    map.insert!(@$genesisAddress, 0, #00)
         |    map.insert!(@$genesisAddress, 1, #01)
         |  }
         |
         |  pub fn checkAndUpdate() -> () {
         |    assert!(map[0] == #00, 0)
         |    assert!(map[1] == #01, 0)
         |    assert!(map.contains!(0) && map.contains!(1), 0)
         |    assert!(!map.contains!(2), 0)
         |    map[0] = #02
         |    map[1] = #03
         |  }
         |
         |  pub fn remove() -> () {
         |    assert!(map[0] == #02, 0)
         |    assert!(map[1] == #03, 0)
         |    map.remove!(@$genesisAddress, 0)
         |    map.remove!(@$genesisAddress, 1)
         |  }
         |}
         |""".stripMargin

    override val mapKeyAndValue = Map(
      Val.U256(0) -> (AVector.empty, AVector(Val.ByteVec(Hex.unsafe("00")))),
      Val.U256(1) -> (AVector.empty, AVector(Val.ByteVec(Hex.unsafe("01"))))
    )
    runTest()
  }

  it should "test primitive array type as map value" in new MapFixture {
    val mapContract =
      s"""
         |Contract MapContract() {
         |  mapping[U256, [ByteVec; 2]] map
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    map.insert!(@$genesisAddress, 0, [#00, #01])
         |    map.insert!(@$genesisAddress, 1, [#02, #03])
         |  }
         |
         |  pub fn checkAndUpdate() -> () {
         |    assert!(map.contains!(0) && map.contains!(1), 0)
         |    assert!(!map.contains!(2), 0)
         |    assert!(map[0][0] == #00 && map[0][1] == #01, 0)
         |    assert!(map[1][0] == #02 && map[1][1] == #03, 0)
         |    let mut number = 0
         |    for (let mut i = 0; i < 2; i = i + 1) {
         |      for (let mut j = 0; j < 2; j = j + 1) {
         |        assert!(map[i][j] == u256To1Byte!(number), 0)
         |        number = number + 1
         |      }
         |    }
         |    map[0] = [#04, #05]
         |    map[1] = [#06, #07]
         |    assert!(map[0][0] == #04 && map[0][1] == #05, 0)
         |    assert!(map[1][0] == #06 && map[1][1] == #07, 0)
         |    map[0][0] = #08
         |    map[0][1] = #09
         |    map[1][0] = #10
         |    map[1][1] = #11
         |  }
         |
         |  pub fn remove() -> () {
         |    assert!(map[0][0] == #08 && map[0][1] == #09, 0)
         |    assert!(map[1][0] == #10 && map[1][1] == #11, 0)
         |    map.remove!(@$genesisAddress, 0)
         |    map.remove!(@$genesisAddress, 1)
         |  }
         |}
         |""".stripMargin

    override val mapKeyAndValue = Map(
      Val.U256(0) -> (AVector.empty, AVector("00", "01").map(s => Val.ByteVec(Hex.unsafe(s)))),
      Val.U256(1) -> (AVector.empty, AVector("02", "03").map(s => Val.ByteVec(Hex.unsafe(s))))
    )
    runTest()
  }

  it should "test struct array type as map value" in new MapFixture {
    val mapContract =
      s"""
         |struct Foo {
         |  mut a: U256,
         |  b: U256
         |}
         |Contract MapContract() {
         |  mapping[U256, [Foo; 2]] map
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    let foo0 = Foo{a: 0, b: 1}
         |    let foo1 = Foo{a: 2, b: 3}
         |    map.insert!(@$genesisAddress, 0, [foo0, foo1])
         |  }
         |
         |  pub fn checkAndUpdate() -> () {
         |    assert!(map.contains!(0), 0)
         |    assert!(!map.contains!(1), 0)
         |    assert!(map[0][0].a == 0 && map[0][0].b == 1, 0)
         |    assert!(map[0][1].a == 2 && map[0][1].b == 3, 0)
         |    let mut number = 0
         |    for (let mut i = 0; i < 2; i = i + 1) {
         |      assert!(map[0][i].a == number && map[0][i].b == (number + 1), 0)
         |      number = number + 2
         |    }
         |    map[0][0].a = 4
         |    map[0][1].a = 5
         |  }
         |
         |  pub fn remove() -> () {
         |    assert!(map[0][0].a == 4 && map[0][0].b == 1, 0)
         |    assert!(map[0][1].a == 5 && map[0][1].b == 3, 0)
         |    map.remove!(@$genesisAddress, 0)
         |  }
         |}
         |""".stripMargin

    val immFields: AVector[Val] = AVector(1, 3).map(v => Val.U256(U256.unsafe(v)))
    val mutFields: AVector[Val] = AVector(0, 2).map(v => Val.U256(U256.unsafe(v)))
    override val mapKeyAndValue = Map(Val.U256(0) -> (immFields, mutFields))
    runTest()
  }

  it should "test immutable struct type as map value" in new MapFixture {
    val mapContract =
      s"""
         |struct Foo {
         |  a: U256,
         |  mut b: ByteVec
         |}
         |struct Bar {
         |  mut c: I256,
         |  mut d: [Foo; 2]
         |}
         |struct Baz {
         |  mut x: Bool,
         |  mut y: [Bar; 2]
         |}
         |Contract MapContract() {
         |  mapping[U256, Baz] map
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    let baz = Baz{
         |      x: false,
         |      y: [
         |        Bar{c: -1i, d: [Foo{a: 1, b: #01}, Foo{a: 2, b: #02}]},
         |        Bar{c: -2i, d: [Foo{a: 3, b: #03}, Foo{a: 4, b: #04}]}
         |      ]
         |    }
         |    map.insert!(@$genesisAddress, 0, baz)
         |  }
         |
         |  pub fn checkAndUpdate() -> () {
         |    assert!(map.contains!(0), 0)
         |    assert!(!map.contains!(1), 0)
         |    f0(0)
         |    map[0].x = true
         |    map[0].y[0].c = -3i
         |    map[0].y[0].d[0].b = #05
         |    map[0].y[0].d[1].b = #06
         |    map[0].y[1].c = -4i
         |    map[0].y[1].d[0].b = #07
         |    map[0].y[1].d[1].b = #08
         |    f1(0)
         |  }
         |
         |  fn f0(key: U256) -> () {
         |    let baz = map[key]
         |    assert!(!baz.x, 0)
         |    assert!(baz.y[0].c == -1i, 0)
         |    assert!(baz.y[0].d[0].a == 1, 0)
         |    assert!(baz.y[0].d[0].b == #01, 0)
         |    assert!(baz.y[0].d[1].a == 2, 0)
         |    assert!(baz.y[0].d[1].b == #02, 0)
         |    assert!(baz.y[1].c == -2i, 0)
         |    assert!(baz.y[1].d[0].a == 3, 0)
         |    assert!(baz.y[1].d[0].b == #03, 0)
         |    assert!(baz.y[1].d[1].a == 4, 0)
         |    assert!(baz.y[1].d[1].b == #04, 0)
         |
         |    assert!(!map[key].x, 0)
         |    assert!(map[key].y[0].c == -1i, 0)
         |    assert!(map[key].y[0].d[0].a == 1, 0)
         |    assert!(map[key].y[0].d[0].b == #01, 0)
         |    assert!(map[key].y[0].d[1].a == 2, 0)
         |    assert!(map[key].y[0].d[1].b == #02, 0)
         |    assert!(map[key].y[1].c == -2i, 0)
         |    assert!(map[key].y[1].d[0].a == 3, 0)
         |    assert!(map[key].y[1].d[0].b == #03, 0)
         |    assert!(map[key].y[1].d[1].a == 4, 0)
         |    assert!(map[key].y[1].d[1].b == #04, 0)
         |  }
         |
         |  fn f1(key: U256) -> () {
         |    assert!(map[key].x, 0)
         |    assert!(map[key].y[0].c == -3i, 0)
         |    assert!(map[key].y[0].d[0].a == 1, 0)
         |    assert!(map[key].y[0].d[0].b == #05, 0)
         |    assert!(map[key].y[0].d[1].a == 2, 0)
         |    assert!(map[key].y[0].d[1].b == #06, 0)
         |    assert!(map[key].y[1].c == -4i, 0)
         |    assert!(map[key].y[1].d[0].a == 3, 0)
         |    assert!(map[key].y[1].d[0].b == #07, 0)
         |    assert!(map[key].y[1].d[1].a == 4, 0)
         |    assert!(map[key].y[1].d[1].b == #08, 0)
         |  }
         |
         |  pub fn remove() -> () {
         |    map.remove!(@$genesisAddress, 0)
         |  }
         |}
         |""".stripMargin

    val immFields: AVector[Val] = AVector(1, 2, 3, 4).map(v => Val.U256(U256.unsafe(v)))
    val mutFields: AVector[Val] = AVector(
      Val.False,
      Val.I256(I256.unsafe(-1)),
      Val.ByteVec(Hex.unsafe("01")),
      Val.ByteVec(Hex.unsafe("02")),
      Val.I256(I256.unsafe(-2)),
      Val.ByteVec(Hex.unsafe("03")),
      Val.ByteVec(Hex.unsafe("04"))
    )
    override val mapKeyAndValue = Map(Val.U256(0) -> (immFields, mutFields))
    runTest()
  }

  it should "test mutable struct type as map value" in new MapFixture {
    val mapContract =
      s"""
         |struct Foo {
         |  mut a: U256,
         |  mut b: ByteVec
         |}
         |struct Bar {
         |  mut c: I256,
         |  mut d: [Foo; 2]
         |}
         |struct Baz {
         |  mut x: Bool,
         |  mut y: [Bar; 2]
         |}
         |Contract MapContract() {
         |  mapping[U256, Baz] map
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    let baz = Baz{
         |      x: false,
         |      y: [
         |        Bar{c: -1i, d: [Foo{a: 1, b: #01}, Foo{a: 2, b: #02}]},
         |        Bar{c: -2i, d: [Foo{a: 3, b: #03}, Foo{a: 4, b: #04}]}
         |      ]
         |    }
         |    map.insert!(@$genesisAddress, 0, baz)
         |  }
         |
         |  pub fn checkAndUpdate() -> () {
         |    assert!(map.contains!(0), 0)
         |    assert!(!map.contains!(1), 0)
         |    f0(0)
         |    f1(0)
         |    f2(0)
         |    f3(0)
         |    f4(0)
         |    f5(0)
         |  }
         |
         |  fn f0(key: U256) -> () {
         |    assert!(!map[key].x, 0)
         |    assert!(map[key].y[0].c == -1i, 0)
         |    assert!(map[key].y[0].d[0].a == 1, 0)
         |    assert!(map[key].y[0].d[0].b == #01, 0)
         |    assert!(map[key].y[0].d[1].a == 2, 0)
         |    assert!(map[key].y[0].d[1].b == #02, 0)
         |    assert!(map[key].y[1].c == -2i, 0)
         |    assert!(map[key].y[1].d[0].a == 3, 0)
         |    assert!(map[key].y[1].d[0].b == #03, 0)
         |    assert!(map[key].y[1].d[1].a == 4, 0)
         |    assert!(map[key].y[1].d[1].b == #04, 0)
         |  }
         |
         |  fn f1(key: U256) -> () {
         |    map[key] = Baz{
         |      x: true,
         |      y: [
         |        Bar{c: -2i, d: [Foo{a: 2, b: #02}, Foo{a: 3, b: #03}]},
         |        Bar{c: -3i, d: [Foo{a: 4, b: #04}, Foo{a: 5, b: #05}]}
         |      ]
         |    }
         |    let baz = map[key]
         |    assert!(baz.x, 0)
         |    assert!(baz.y[0].c == -2i, 0)
         |    assert!(baz.y[0].d[0].a == 2, 0)
         |    assert!(baz.y[0].d[0].b == #02, 0)
         |    assert!(baz.y[0].d[1].a == 3, 0)
         |    assert!(baz.y[0].d[1].b == #03, 0)
         |    assert!(baz.y[1].c == -3i, 0)
         |    assert!(baz.y[1].d[0].a == 4, 0)
         |    assert!(baz.y[1].d[0].b == #04, 0)
         |    assert!(baz.y[1].d[1].a == 5, 0)
         |    assert!(baz.y[1].d[1].b == #05, 0)
         |  }
         |
         |  fn f2(key: U256) -> () {
         |    map[key].x = false
         |    map[key].y[0] = Bar{c: -3i, d: [Foo{a: 3, b: #03}, Foo{a: 4, b: #04}]}
         |    map[key].y[1] = Bar{c: -4i, d: [Foo{a: 5, b: #05}, Foo{a: 6, b: #06}]}
         |    let baz = map[key]
         |    assert!(!baz.x, 0)
         |    assert!(baz.y[0].c == -3i, 0)
         |    assert!(baz.y[0].d[0].a == 3, 0)
         |    assert!(baz.y[0].d[0].b == #03, 0)
         |    assert!(baz.y[0].d[1].a == 4, 0)
         |    assert!(baz.y[0].d[1].b == #04, 0)
         |    assert!(baz.y[1].c == -4i, 0)
         |    assert!(baz.y[1].d[0].a == 5, 0)
         |    assert!(baz.y[1].d[0].b == #05, 0)
         |    assert!(baz.y[1].d[1].a == 6, 0)
         |    assert!(baz.y[1].d[1].b == #06, 0)
         |  }
         |
         |  fn f3(key: U256) -> () {
         |    map[key].x = true
         |    map[key].y[0].c = -4i
         |    map[key].y[0].d = [Foo{a: 4, b: #04}, Foo{a: 5, b: #05}]
         |    map[key].y[1].c = -5i
         |    map[key].y[1].d = [Foo{a: 6, b: #06}, Foo{a: 7, b: #07}]
         |    let baz = map[key]
         |    assert!(baz.x, 0)
         |    assert!(baz.y[0].c == -4i, 0)
         |    assert!(baz.y[0].d[0].a == 4, 0)
         |    assert!(baz.y[0].d[0].b == #04, 0)
         |    assert!(baz.y[0].d[1].a == 5, 0)
         |    assert!(baz.y[0].d[1].b == #05, 0)
         |    assert!(baz.y[1].c == -5i, 0)
         |    assert!(baz.y[1].d[0].a == 6, 0)
         |    assert!(baz.y[1].d[0].b == #06, 0)
         |    assert!(baz.y[1].d[1].a == 7, 0)
         |    assert!(baz.y[1].d[1].b == #07, 0)
         |  }
         |
         |  fn f4(key: U256) -> () {
         |    map[key].x = false
         |    map[key].y[0].c = -5i
         |    map[key].y[0].d[0] = Foo{a: 5, b: #05}
         |    map[key].y[0].d[1] = Foo{a: 6, b: #06}
         |    map[key].y[1].c = -6i
         |    map[key].y[1].d[0] = Foo{a: 7, b: #07}
         |    map[key].y[1].d[1] = Foo{a: 8, b: #08}
         |    let baz = map[key]
         |    assert!(!baz.x, 0)
         |    assert!(baz.y[0].c == -5i, 0)
         |    assert!(baz.y[0].d[0].a == 5, 0)
         |    assert!(baz.y[0].d[0].b == #05, 0)
         |    assert!(baz.y[0].d[1].a == 6, 0)
         |    assert!(baz.y[0].d[1].b == #06, 0)
         |    assert!(baz.y[1].c == -6i, 0)
         |    assert!(baz.y[1].d[0].a == 7, 0)
         |    assert!(baz.y[1].d[0].b == #07, 0)
         |    assert!(baz.y[1].d[1].a == 8, 0)
         |    assert!(baz.y[1].d[1].b == #08, 0)
         |  }
         |
         |  fn f5(key: U256) -> () {
         |    map[key].x = true
         |    map[key].y[0].c = -6i
         |    map[key].y[0].d[0].a = 6
         |    map[key].y[0].d[0].b = #06
         |    map[key].y[0].d[1].a = 7
         |    map[key].y[0].d[1].b = #07
         |    map[key].y[1].c = -7i
         |    map[key].y[1].d[0].a = 8
         |    map[key].y[1].d[0].b = #08
         |    map[key].y[1].d[1].a = 9
         |    map[key].y[1].d[1].b = #09
         |    let baz = map[key]
         |    assert!(baz.x, 0)
         |    assert!(baz.y[0].c == -6i, 0)
         |    assert!(baz.y[0].d[0].a == 6, 0)
         |    assert!(baz.y[0].d[0].b == #06, 0)
         |    assert!(baz.y[0].d[1].a == 7, 0)
         |    assert!(baz.y[0].d[1].b == #07, 0)
         |    assert!(baz.y[1].c == -7i, 0)
         |    assert!(baz.y[1].d[0].a == 8, 0)
         |    assert!(baz.y[1].d[0].b == #08, 0)
         |    assert!(baz.y[1].d[1].a == 9, 0)
         |    assert!(baz.y[1].d[1].b == #09, 0)
         |  }
         |
         |  pub fn remove() -> () {
         |    map.remove!(@$genesisAddress, 0)
         |  }
         |}
         |""".stripMargin

    val immFields: AVector[Val] = AVector.empty
    val mutFields: AVector[Val] = AVector(
      Val.False,
      Val.I256(I256.unsafe(-1)),
      Val.U256(U256.unsafe(1)),
      Val.ByteVec(Hex.unsafe("01")),
      Val.U256(U256.unsafe(2)),
      Val.ByteVec(Hex.unsafe("02")),
      Val.I256(I256.unsafe(-2)),
      Val.U256(U256.unsafe(3)),
      Val.ByteVec(Hex.unsafe("03")),
      Val.U256(U256.unsafe(4)),
      Val.ByteVec(Hex.unsafe("04"))
    )
    override val mapKeyAndValue = Map(Val.U256(0) -> (immFields, mutFields))
    runTest()
  }

  it should "test multiple maps" in new MapFixture {
    val mapContract =
      s"""
         |Contract Foo() {
         |  mapping[U256, U256] map0
         |  mapping[U256, U256] map1
         |  @using(preapprovedAssets = true)
         |  pub fn insertToMap0(key: U256, value: U256) -> () {
         |    map0.insert!(@$genesisAddress, key, value)
         |  }
         |  @using(preapprovedAssets = true)
         |  pub fn insertToMap1(key: U256, value: U256) -> () {
         |    map1.insert!(@$genesisAddress, key, value)
         |  }
         |  pub fn updateMap0(key: U256, oldValue: U256, newValue: U256) -> () {
         |    assert!(map0[key] == oldValue, 0)
         |    map0[key] = newValue
         |  }
         |  pub fn updateMap1(key: U256, oldValue: U256, newValue: U256) -> () {
         |    assert!(map1[key] == oldValue, 0)
         |    map1[key] = newValue
         |  }
         |  pub fn removeFromMap0(key: U256) -> () {
         |    map0.remove!(@$genesisAddress, key)
         |  }
         |  pub fn removeFromMap1(key: U256) -> () {
         |    map1.remove!(@$genesisAddress, key)
         |  }
         |}
         |""".stripMargin

    def insertToMap(mapIndex: Int, key: Int, value: Int) = {
      val code =
        s"""
           |TxScript Main() {
           |  let foo = Foo(#${mapContractId.toHexString})
           |  foo.insertToMap$mapIndex{@$genesisAddress -> ALPH: mapEntryDeposit!()}($key, $value)
           |}
           |$mapContract
           |""".stripMargin
      callTxScript(code)
      checkSubContractState(
        Val.U256(U256.unsafe(key)),
        AVector.empty,
        AVector(Val.U256(U256.unsafe(value))),
        mapIndex
      )
    }

    def updateMap(mapIndex: Int, key: Int, oldValue: Int, newValue: Int) = {
      val code =
        s"""
           |TxScript Main() {
           |  let foo = Foo(#${mapContractId.toHexString})
           |  foo.updateMap$mapIndex($key, $oldValue, $newValue)
           |}
           |$mapContract
           |""".stripMargin
      callTxScript(code)
      checkSubContractState(
        Val.U256(U256.unsafe(key)),
        AVector.empty,
        AVector(Val.U256(U256.unsafe(newValue))),
        mapIndex
      )
    }

    def removeFromMap(mapIndex: Int, key: Int) = {
      val code =
        s"""
           |TxScript Main() {
           |  let foo = Foo(#${mapContractId.toHexString})
           |  foo.removeFromMap$mapIndex($key)
           |}
           |$mapContract
           |""".stripMargin
      callTxScript(code)
      subContractNotExist(Val.U256(U256.unsafe(key)), mapIndex)
    }

    insertToMap(0, 1, 1)
    insertToMap(1, 1, 2)
    updateMap(0, 1, 1, 3)
    updateMap(1, 1, 2, 4)
    removeFromMap(0, 1)
    removeFromMap(1, 1)
  }

  it should "check caller contract id when calling generated map contract functions" in new MapFixture {
    val mapContract =
      s"""
         |struct Bar { mut a: U256, b: U256 }
         |Contract Foo() {
         |  mapping[U256, Bar] map
         |  pub fn readB() -> () {
         |    assert!(map[0].b == 0, 0)
         |  }
         |
         |  pub fn readA() -> () {
         |    assert!(map[0].a == 0, 0)
         |  }
         |
         |  pub fn update() -> () {
         |    map[0].a = 1
         |  }
         |
         |  pub fn remove() -> () {
         |    map.remove!(@$genesisAddress, 0)
         |  }
         |
         |  @using(preapprovedAssets = true)
         |  pub fn insert() -> () {
         |    map.insert!(@$genesisAddress, 0, Bar { a: 0, b: 0 })
         |  }
         |}
         |""".stripMargin

    val insertScript =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${mapContractId.toHexString})
         |  foo.insert{@$genesisAddress -> ALPH: mapEntryDeposit!()}()
         |}
         |$mapContract
         |""".stripMargin
    callTxScript(insertScript)
    val mapKey        = Val.U256(U256.Zero)
    val subContractId = calcSubContractId(mapKey)
    val invalidCallerContract = {
      val loadImmFieldInstrs = AVector[Instr[StatefulContext]](
        ConstInstr.u256(Val.U256(U256.Zero)), // the index of `Bar.b`
        ConstInstr.u256(Val.U256(U256.One)),
        ConstInstr.u256(Val.U256(U256.One)),
        BytesConst(Val.ByteVec(subContractId.bytes)),
        CallExternal(CreateMapEntry.LoadImmFieldMethodIndex)
      )
      val loadImmFieldMethod = Method(true, false, false, false, 0, 0, 0, loadImmFieldInstrs)
      val loadMutFieldInstrs = AVector[Instr[StatefulContext]](
        ConstInstr.u256(Val.U256(U256.Zero)), // the index of `Bar.a`
        ConstInstr.u256(Val.U256(U256.One)),
        ConstInstr.u256(Val.U256(U256.One)),
        BytesConst(Val.ByteVec(subContractId.bytes)),
        CallExternal(CreateMapEntry.LoadMutFieldMethodIndex)
      )
      val loadMutFieldMethod = Method(true, false, false, false, 0, 0, 0, loadMutFieldInstrs)
      val storeFieldInstrs = AVector[Instr[StatefulContext]](
        ConstInstr.u256(Val.U256(U256.One)),  // new value
        ConstInstr.u256(Val.U256(U256.Zero)), // mutable field index
        ConstInstr.u256(Val.U256(U256.Two)),
        ConstInstr.u256(Val.U256(U256.Zero)),
        BytesConst(Val.ByteVec(subContractId.bytes)),
        CallExternal(CreateMapEntry.StoreMutFieldMethodIndex)
      )
      val storeFieldMethod = Method(true, false, false, false, 0, 0, 0, storeFieldInstrs)
      val destroyInstrs = AVector[Instr[StatefulContext]](
        AddressConst(Val.Address(genesisAddress.lockupScript)),
        ConstInstr.u256(Val.U256(U256.One)),
        ConstInstr.u256(Val.U256(U256.Zero)),
        BytesConst(Val.ByteVec(subContractId.bytes)),
        CallExternal(CreateMapEntry.DestroyMethodIndex)
      )
      val destroyMethod = Method(true, false, false, false, 0, 0, 0, destroyInstrs)
      StatefulContract(
        0,
        AVector(loadImmFieldMethod, loadMutFieldMethod, storeFieldMethod, destroyMethod)
      )
    }

    val invalidCallerId = createCompiledContract(invalidCallerContract)._1
    def createCallScript(callerContractId: ContractId, methodIndex: Int) = {
      val callInstrs = AVector[Instr[StatefulContext]](
        ConstInstr.u256(Val.U256(U256.Zero)),
        ConstInstr.u256(Val.U256(U256.Zero)),
        BytesConst(Val.ByteVec(callerContractId.bytes)),
        CallExternal(methodIndex.toByte)
      )
      StatefulScript.unsafe(AVector(Method(true, true, false, false, 0, 0, 0, callInstrs)))
    }

    failCallTxScript(createCallScript(invalidCallerId, 0), AssertionFailed) // load `Bar.b`
    failCallTxScript(createCallScript(invalidCallerId, 1), AssertionFailed) // load `Bar.a`
    failCallTxScript(createCallScript(invalidCallerId, 2), AssertionFailed) // update `Bar.a`
    failCallTxScript(createCallScript(invalidCallerId, 3), AssertionFailed) // destroy map entry

    checkSubContractState(mapKey, AVector(Val.U256(U256.Zero)), AVector(Val.U256(U256.Zero)))
    callCompiledTxScript(createCallScript(mapContractId, 0))
    callCompiledTxScript(createCallScript(mapContractId, 1))
    callCompiledTxScript(createCallScript(mapContractId, 2))
    checkSubContractState(mapKey, AVector(Val.U256(U256.Zero)), AVector(Val.U256(U256.One)))
    callCompiledTxScript(createCallScript(mapContractId, 3))
    subContractNotExist(mapKey)
  }

  it should "test maximum field length for map value" in new ContractFixture {
    val maxFieldSize = 0xff
    def code(mutable: Boolean) = {
      // there is a immutable parent contract id field
      val fieldSize = if (mutable) maxFieldSize else maxFieldSize - 1
      val prefix    = if (mutable) "mut " else ""
      s"""
         |// use `Bool` to avoid the `FieldsSizeTooLarge` error
         |struct Foo { $prefix value: [Bool; $fieldSize] }
         |Contract Bar() {
         |  mapping[U256, Foo] map
         |
         |  @using(preapprovedAssets = true)
         |  pub fn test() -> () {
         |    map.insert!(
         |      @$genesisAddress,
         |      0,
         |      Foo { value: [true; ${fieldSize}] }
         |    )
         |    for (let mut i = 0; i < ${fieldSize}; i = i + 1) {
         |      assert!(map[0].value[i], 0)
         |    }
         |  }
         |}
         |""".stripMargin
    }

    def test(mutable: Boolean) = {
      val contractCode = code(mutable)
      val contractId   = createContract(contractCode)._1
      val script =
        s"""
           |TxScript Main {
           |  let bar = Bar(#${contractId.toHexString})
           |  bar.test{@$genesisAddress -> ALPH: mapEntryDeposit!()}()
           |}
           |$contractCode
           |""".stripMargin
      callTxScript(script)
    }

    test(true)
    test(false)
  }

  it should "insert/remove map entries using contract assets" in new ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  mapping[U256, U256] map
         |  @using(assetsInContract = true, checkExternalCaller = false)
         |  pub fn insert(key: U256, value: U256) -> () {
         |    map.insert!(selfAddress!(), key, value)
         |  }
         |
         |  @using(assetsInContract = true, checkExternalCaller = false)
         |  pub fn remove(key: U256) -> () {
         |    map.remove!(selfAddress!(), key)
         |  }
         |}
         |""".stripMargin
    val entrySize     = 4
    val initialAmount = minimalAlphInContract * entrySize
    val fooId         = createContract(foo, initialAttoAlphAmount = initialAmount)._1

    def insert(idx: Int) = {
      val script =
        s"""
           |TxScript Main {
           |  let foo = Foo(#${fooId.toHexString})
           |  foo.insert($idx, $idx)
           |}
           |$foo
           |""".stripMargin
      callTxScript(script)
    }

    def remove(key: Int) = {
      val script =
        s"""
           |TxScript Main {
           |  let foo = Foo(#${fooId.toHexString})
           |  foo.remove($key)
           |}
           |$foo
           |""".stripMargin
      callTxScript(script)
    }

    (0 until entrySize - 1).foreach { idx =>
      insert(idx)
      getContractAsset(fooId).amount is (initialAmount - minimalAlphInContract * (idx + 1))
    }
    intercept[AssertionError](insert(entrySize - 1)).getMessage is
      s"Right(TxScriptExeFailed($EmptyContractAsset))"

    (0 until entrySize - 1).foreach { idx =>
      remove(idx)
      getContractAsset(fooId).amount is minimalAlphInContract * (idx + 2)
    }
    getContractAsset(fooId).amount is initialAmount
  }

  behavior of "Reentrancy protection"

  trait ReentrancyFixture extends ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  @using(assetsInContract = true)
         |  pub fn withdraw0(target: Address) -> () {
         |    transferTokenFromSelf!(target, ALPH, 1 alph)
         |  }
         |  @using(assetsInContract = true)
         |  pub fn withdraw1(target: Address) -> () {
         |    transferTokenFromSelf!(target, ALPH, 1 alph)
         |  }
         |  @using(assetsInContract = true)
         |  pub fn withdraw2(target: Address) -> () {
         |    transferTokenFromSelf!(target, ALPH, 1 alph)
         |    withdraw1(target)
         |  }
         |}
         |""".stripMargin

    lazy val fooId = createContract(foo, initialAttoAlphAmount = ALPH.alph(10))._1

    lazy val script =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  foo.withdraw0(callerAddress!())
         |  foo.withdraw2(callerAddress!())
         |}
         |$foo
         |""".stripMargin
  }

  it should "call multiple asset functions in the same contract: Rhone" in new ReentrancyFixture {
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Ghost
    callTxScript(script)
    getContractAsset(fooId).amount is ALPH.alph(10 - 3)
  }

  it should "not call multiple asset functions in the same contract: Leman" in new ReentrancyFixture {
    override val configValues =
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman
    failCallTxScript(script, ContractAssetAlreadyInUsing)
  }

  behavior of "Pay to contract only"

  trait PayToContractOnlyFixture extends ContractFixture {
    val foo =
      s"""
         |Contract Foo() {
         |  @using(preapprovedAssets = true, payToContractOnly = true)
         |  pub fn deposit(sender: Address) -> () {
         |    transferTokenToSelf!(sender, ALPH, 10 alph)
         |  }
         |  @using(preapprovedAssets = true, payToContractOnly = true)
         |  pub fn depositN(sender: Address, n: U256) -> () {
         |    transferTokenToSelf!(sender, ALPH, 10 alph)
         |    if (n > 0) {
         |      depositN{sender -> ALPH: 10 alph * n}(sender, n - 1)
         |    }
         |  }
         |  @using(preapprovedAssets = true, assetsInContract = true)
         |  pub fn both(sender: Address, target: Address) -> () {
         |    transferTokenFromSelf!(target, ALPH, 1 alph)
         |    depositN{sender -> ALPH: 10 alph * 6}(target, 5)
         |  }
         |}
         |""".stripMargin

    lazy val fooId = createContract(foo, initialAttoAlphAmount = ALPH.alph(100))._1

    lazy val script =
      s"""
         |TxScript Main {
         |  let foo = Foo(#${fooId.toHexString})
         |  let caller = callerAddress!()
         |  foo.deposit{caller -> ALPH: 10 alph}(caller)
         |  foo.deposit{caller -> ALPH: 10 alph}(caller)
         |  foo.both{caller -> ALPH: 10 alph * 6}(caller, caller)
         |}
         |$foo
         |""".stripMargin
  }

  it should "call the same deposit function multiple times in the same contract: Rhone" in new PayToContractOnlyFixture {
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Ghost

    getContractAsset(fooId).amount is ALPH.alph(100)
    callTxScript(script)
    getContractAsset(fooId).amount is ALPH.alph(100 + 20 + 10 * 6 - 1)
  }

  it should "not call the same deposit function multiple times in the same contract: Leman" in new PayToContractOnlyFixture {
    override val configValues =
      Map(("alephium.network.ghost-hard-fork-timestamp", TimeStamp.Max.millis))
    networkConfig.getHardFork(TimeStamp.now()) is HardFork.Leman

    intercept[AssertionError](callTxScript(script)).getMessage is
      s"Right(TxScriptExeFailed($InvalidMethodModifierBeforeRhone))"
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
