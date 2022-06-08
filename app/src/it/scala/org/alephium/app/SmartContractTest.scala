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

package org.alephium.app

import akka.util.ByteString
import org.scalatest.Assertion
import sttp.model.StatusCode

import org.alephium.api.model._
import org.alephium.flow.gasestimation._
import org.alephium.json.Json._
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{Val => _, _}
import org.alephium.serde.deserialize
import org.alephium.util._

class SmartContractTest extends AlephiumActorSpec {

  trait SwapContractsFixture extends CliqueFixture {
    val clique = bootClique(nbOfNodes = 2)
    clique.start()

    val selfClique = clique.selfClique()
    val group      = request[Group](getGroup(address), clique.masterRestPort)
    val index      = group.group % selfClique.brokerNum
    val restPort   = selfClique.nodes(index).restPort

    def checkUTXOs(check: Set[(U256, AVector[Token])] => Assertion) = {
      val currentUTXOs = request[UTXOs](getUTXOs(address), restPort)
      check {
        currentUTXOs.utxos.map { utxo =>
          (utxo.amount.value, utxo.tokens)
        }.toSet
      }
    }

    def contract(
        code: String,
        initialFields: Option[AVector[vm.Val]],
        issueTokenAmount: Option[U256],
        gas: Option[Int] = Some(100000),
        gasPrice: Option[GasPrice] = None
    ): BuildDeployContractTxResult = {
      val compileResult = request[CompileContractResult](compileContract(code), restPort)
      val buildResult = request[BuildDeployContractTxResult](
        buildDeployContractTx(
          fromPublicKey = publicKey,
          code = compileResult.bytecode,
          gas,
          gasPrice,
          initialFields = initialFields,
          issueTokenAmount = issueTokenAmount
        ),
        restPort
      )
      submitTx(buildResult.unsignedTx, buildResult.txId)
      buildResult
    }

    def submitTx(unsignedTx: String, txId: Hash): Hash = {
      submitTxWithPort(unsignedTx, txId, restPort)
    }

    def script(
        code: String,
        alphAmount: Option[Amount] = None,
        gas: Option[Int] = Some(100000),
        gasPrice: Option[GasPrice] = None
    ): BuildExecuteScriptTxResult = {
      scriptWithPort(code, restPort, alphAmount, gas, gasPrice)
    }

    def buildExecuteScriptTx(
        code: String,
        alphAmount: Option[Amount],
        gas: Option[Int],
        gasPrice: Option[GasPrice]
    ): BuildExecuteScriptTxResult = {
      buildExecuteScriptTxWithPort(code, restPort, alphAmount, gas, gasPrice)
    }

    def estimateBuildContractGas(
        code: String,
        state: Option[String],
        issueTokenAmount: Option[U256]
    ): GasBox = {
      val unlockScript = UnlockScript.p2pkh(PublicKey.from(Hex.unsafe(publicKey)).value)
      val lockupScript = LockupScript.p2pkh(PublicKey.from(Hex.unsafe(publicKey)).value)

      val compileResult = request[CompileContractResult](compileContract(code), restPort)
      val script = ServerUtils
        .buildDeployContractTx(
          compileResult.bytecode,
          Address.fromBase58(address).value,
          state,
          minimalAlphInContract,
          AVector.empty,
          issueTokenAmount
        )
        .rightValue

      val blockFlow    = clique.servers(group.group % 2).node.blockFlow
      val allUtxos     = blockFlow.getUsableUtxos(lockupScript, 100).rightValue
      val allInputs    = allUtxos.map(_.ref).map(TxInput(_, unlockScript))
      val gasEstimator = TxScriptGasEstimator.Default(allInputs, blockFlow)

      GasEstimation.estimate(script, gasEstimator).rightValue
    }

    def estimateTxScriptGas(
        code: String
    ): GasBox = {
      val unlockScript = UnlockScript.p2pkh(PublicKey.from(Hex.unsafe(publicKey)).value)
      val lockupScript = LockupScript.p2pkh(PublicKey.from(Hex.unsafe(publicKey)).value)

      val compileResult = request[CompileScriptResult](compileScript(code), restPort)
      val script = deserialize[StatefulScript](
        Hex.unsafe(compileResult.bytecodeTemplate)
      ).rightValue

      val blockFlow    = clique.servers(group.group % 2).node.blockFlow
      val allUtxos     = blockFlow.getUsableUtxos(lockupScript, 10000).rightValue
      val allInputs    = allUtxos.map(_.ref).map(TxInput(_, unlockScript))
      val gasEstimator = TxScriptGasEstimator.Default(allInputs, blockFlow)

      GasEstimation.estimate(script, gasEstimator).rightValue
    }

    def decodeTx(str: String): UnsignedTx = {
      request[DecodeUnsignedTxResult](decodeUnsignedTransaction(str), restPort).unsignedTx
    }

    def verifySpentUTXOs(unsignedTx: String, hashes: Set[String]) = {
      val tx = decodeTx(unsignedTx)
      tx.inputs.map(_.outputRef.key.toHexString).toSet is hashes
    }

    def noTokens: AVector[Token] = AVector.empty

    request[Balance](getBalance(address), restPort) is initialBalance
    startWS(defaultWsMasterPort)
    selfClique.nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }
    request[Balance](getBalance(address), restPort) is initialBalance
  }

  it should "compile contract failed when have invalid state length" in new CliqueFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))
    val clique                = bootClique(1)
    clique.start()

    val restPort = clique.masterRestPort
    val contract =
      s"""
         |TxContract Foo(a: Bool, b: I256, c: U256, d: ByteVec, e: Address) {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    val compileResult = request[CompileContractResult](compileContract(contract), restPort)
    val validFields = Some(
      AVector[vm.Val](
        vm.Val.True,
        vm.Val.I256(I256.unsafe(1000)),
        vm.Val.U256(U256.unsafe(1000)),
        vm.Val.ByteVec(ByteString(0, 0)),
        vm.Val.Address(
          Address.fromBase58("25HAxb2jhQeLUFcTL8XWpz9m1odVcD2LgscQtuGhRBerR").get.lockupScript
        )
      )
    )
    unitRequest(
      buildDeployContractTx(
        publicKey,
        compileResult.bytecode,
        initialFields = validFields
      ),
      restPort
    )

    val invalidFields = Some(AVector[vm.Val](vm.Val.True))
    requestFailed(
      buildDeployContractTx(
        publicKey,
        compileResult.bytecode,
        initialFields = invalidFields
      ),
      restPort,
      StatusCode.BadRequest
    )

    clique.stop()
  }

  it should "estimate gas for build contract correctly" in new SwapContractsFixture {
    val tokenContractBuildResult =
      contract(
        SwapContracts.tokenContract,
        gas = None,
        initialFields = Some(AVector[vm.Val](vm.Val.U256(U256.unsafe(0)))),
        issueTokenAmount = Some(1024)
      )

    val rawUnsignedTx = Hex.from(tokenContractBuildResult.unsignedTx).value
    val unsignedTx    = deserialize[UnsignedTransaction](rawUnsignedTx).rightValue

    val scriptGas = estimateBuildContractGas(SwapContracts.tokenContract, Some("[0u]"), Some(1024))
    val gasWithoutScript = GasEstimation.estimateWithP2PKHInputs(
      unsignedTx.inputs.length,
      unsignedTx.fixedOutputs.length
    )

    gasWithoutScript.addUnsafe(scriptGas) is unsignedTx.gasAmount
    unsignedTx.gasAmount is GasBox.unsafe(57068)

    clique.stop()
  }

  it should "estimate gas for build script correctly" in new SwapContractsFixture {
    val tokenContractBuildResult =
      contract(
        SwapContracts.tokenContract,
        gas = Some(100000),
        initialFields = Some(AVector[vm.Val](vm.Val.U256(U256.unsafe(0)))),
        issueTokenAmount = Some(1024)
      )
    val tokenContractKey = tokenContractBuildResult.contractAddress.contractId

    val tokenWithdrawScript = {
      SwapContracts.tokenWithdrawTxScript(address, tokenContractKey, U256.unsafe(1024))
    }
    val tokenWithdrawTxScriptResult = buildExecuteScriptTx(tokenWithdrawScript, None, None, None)

    val rawUnsignedTx = Hex.from(tokenWithdrawTxScriptResult.unsignedTx).value
    val unsignedTx    = deserialize[UnsignedTransaction](rawUnsignedTx).rightValue

    val scriptGas = estimateTxScriptGas(tokenWithdrawScript)
    val gasWithoutScript = GasEstimation.estimateWithP2PKHInputs(
      unsignedTx.inputs.length,
      unsignedTx.fixedOutputs.length
    )

    gasWithoutScript.addUnsafe(scriptGas) is unsignedTx.gasAmount
    unsignedTx.gasAmount is GasBox.unsafe(32342)

    clique.stop()
  }

  it should "compile/execute the swap contracts successfully" in new SwapContractsFixture {

    info("Create token contract")
    val tokenContractBuildResult =
      contract(
        SwapContracts.tokenContract,
        gas = Some(100000),
        initialFields = Some(AVector[vm.Val](vm.Val.U256(U256.unsafe(0)))),
        issueTokenAmount = Some(1024)
      )
    val tokenContractKey = tokenContractBuildResult.contractAddress.contractId

    info("Transfer 1024 token back to self")
    script(SwapContracts.tokenWithdrawTxScript(address, tokenContractKey, U256.unsafe(1024)))

    info("Create the ALPH/token swap contract")
    val swapContractBuildResult = contract(
      SwapContracts.swapContract,
      Some(
        AVector[vm.Val](
          vm.Val.ByteVec(tokenContractKey.bytes),
          vm.Val.U256(U256.Zero),
          vm.Val.U256(U256.Zero)
        )
      ),
      issueTokenAmount = Some(10000)
    )
    val swapContractKey = swapContractBuildResult.contractAddress.contractId

    info("Add liquidity to the swap contract")
    script(
      SwapContracts.addLiquidityTxScript(
        address,
        ALPH.alph(10),
        tokenContractKey,
        U256.unsafe(100),
        swapContractKey
      )
    )

    info("Swap ALPH with tokens")
    script(SwapContracts.swapAlphForTokenTxScript(address, swapContractKey, ALPH.alph(10)))

    info("Swap tokens with ALPH")
    script(
      SwapContracts.swapTokenForAlphTxScript(
        address,
        swapContractKey,
        tokenContractKey,
        U256.unsafe(50)
      )
    )

    eventually {
      request[Balance](getBalance(address), restPort) isnot initialBalance
    }
    selfClique.nodes.foreach { peer => request[Boolean](stopMining, peer.restPort) is true }
    clique.stop()
  }

  it should "compile/execute the swap contracts successfully, and check the UTXOs" in new SwapContractsFixture {

    info("Transfer some ALPH to self, creating 5 UTXOs")
    val assetAddress                           = Address.asset(address).value
    def destination(amount: U256): Destination = Destination(assetAddress, Amount(amount))
    transfer(
      publicKey,
      AVector(
        destination(ALPH.alph(100)),
        destination(ALPH.alph(1000)),
        destination(ALPH.alph(10000)),
        destination(ALPH.alph(100000))
      ),
      privateKey,
      restPort
    )

    checkUTXOs { currentUTXOs =>
      val changeAmount = ALPH
        .alph(1000000)
        .subUnsafe(ALPH.alph(100))
        .subUnsafe(ALPH.alph(1000))
        .subUnsafe(ALPH.alph(10000))
        .subUnsafe(ALPH.alph(100000))
        .subUnsafe(defaultGasPrice * GasEstimation.estimateWithP2PKHInputs(1, 5))

      changeAmount is ALPH.nanoAlph(888899997244000L)

      currentUTXOs is Set(
        (ALPH.alph(100), noTokens),
        (ALPH.alph(1000), noTokens),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (changeAmount, noTokens)
      )
    }

    info("Create token contract")
    val tokenContractBuildResult =
      contract(
        SwapContracts.tokenContract,
        gas = Some(100000),
        initialFields = Some(AVector[vm.Val](vm.Val.U256(U256.Zero))),
        issueTokenAmount = Some(1024)
      )
    val tokenContractKey = tokenContractBuildResult.contractAddress.contractId

    checkUTXOs { currentUTXOs =>
      // Create the token contract
      //   - one ALPH is deposit into contract     [ALPH.alph(1)]
      //   - Gas fee: defaultGasPrice  * 100000    [ALPH.nanoAlph(10000000)]
      val updatedAmount = ALPH
        .alph(100)
        .subUnsafe(minimalAlphInContract)
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(98990000000L)

      currentUTXOs is Set(
        (updatedAmount, noTokens),
        (ALPH.alph(1000), noTokens),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (ALPH.nanoAlph(888899997244000L), noTokens)
      )
    }

    def token(amount: Int) = AVector(Token(tokenContractKey, U256.unsafe(amount)))

    info("Transfer 1024 token back to self")
    script(
      SwapContracts.tokenWithdrawTxScript(address, tokenContractKey, U256.unsafe(1024))
    )

    checkUTXOs { currentUTXOs =>
      // Withdraw 1024 tokens from the token contract
      //   - 0 ALPH is sent out                    [ALPH.nanoAlph(0)]
      //   - 1024 token is received                [token(1024)]
      //   - Gas fee: defaultGasPrice  * 100000    [ALPH.nanoAlph(10000000)]
      val updatedAmount = ALPH
        .nanoAlph(98990000000L)
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(98980000000L)

      currentUTXOs is Set(
        (ALPH.nanoAlph(98980000000L), token(1024)),
        (ALPH.alph(1000), noTokens),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (ALPH.nanoAlph(888899997244000L), noTokens)
      )
    }

    info("Create the ALPH/token swap contract")
    val swapContractBuildResult = contract(
      SwapContracts.swapContract,
      Some(
        AVector[vm.Val](
          vm.Val.ByteVec(tokenContractKey.bytes),
          vm.Val.U256(U256.Zero),
          vm.Val.U256(U256.Zero)
        )
      ),
      issueTokenAmount = Some(10000)
    )
    val swapContractKey = swapContractBuildResult.contractAddress.contractId

    checkUTXOs { currentUTXOs =>
      // Create the swap contract
      //   - dustUtxoAmount of ALPH is sent out     [ALPH.nanoAlph(1000)]
      //   - Gas fee: defaultGasPrice  * 100000     [ALPH.nanoAlph(10000000)]
      val updatedAmount = ALPH
        .nanoAlph(98980000000L)
        .subUnsafe(minimalAlphInContract)
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(97970000000L)

      currentUTXOs is Set(
        (updatedAmount, token(1024)),
        (ALPH.alph(1000), noTokens),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (ALPH.nanoAlph(888899997244000L), noTokens)
      )
    }

    info("Add liquidity to the swap contract")
    script(
      SwapContracts.addLiquidityTxScript(
        address,
        ALPH.alph(100),
        tokenContractKey,
        U256.unsafe(1000),
        swapContractKey
      ),
      Some(Amount(ALPH.alph(100)))
    )

    checkUTXOs { currentUTXOs =>
      // Add liquidity through the swap contract
      //   - 100 ALPH is sent out                   [ALPH.alph(10)]
      //   - 100 tokens is sent out                 [token(1000)]
      //   - Gas fee: defaultGasPrice  * 100000     [ALPH.nanoAlph(10000000)]
      val updatedAmount = ALPH
        .nanoAlph(
          97970000000L
        ) // from UTXO: 8a28d6a00e58d5cab36b94b12119da550351f71a530842a0d7ca712633cc0b9d
        .addUnsafe(
          ALPH.alph(1000)
        ) // from UTXO: 0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced
        .subUnsafe(ALPH.alph(100))
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(997960000000L)

      currentUTXOs is Set(
        (ALPH.nanoAlph(997960000000L), token(24)),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (ALPH.nanoAlph(888899997244000L), noTokens)
      )
    }

    info("Swap ALPH with tokens")
    script(
      SwapContracts.swapAlphForTokenTxScript(address, swapContractKey, ALPH.alph(100))
    )

    checkUTXOs { currentUTXOs =>
      // Swap 100 ALPH with tokens through the swap contract
      //   - 100 ALPH is sent out                   [ALPH.alph(10)]
      //   - 500 tokens is received                 [token(50)]
      //   - Gas fee: defaultGasPrice  * 100000     [ALPH.nanoAlph(10000000)]
      val updatedAmount = ALPH
        .nanoAlph(997960000000L)
        .subUnsafe(ALPH.alph(100))
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(897950000000L)

      currentUTXOs is Set(
        (ALPH.nanoAlph(897950000000L), token(524)),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (ALPH.nanoAlph(888899997244000L), noTokens)
      )
    }

    info("Swap tokens with ALPH")
    script(
      SwapContracts.swapTokenForAlphTxScript(
        address,
        swapContractKey,
        tokenContractKey,
        U256.unsafe(500)
      )
    )

    checkUTXOs { currentUTXOs =>
      // Swap 500 tokens with ALPH through the swap contract
      //   - 100 ALPH is received                    [ALPH.alph(10)]
      //   - 500 tokens is sent out                  [token(50)]
      //   - Gas fee: defaultGasPrice  * 100000      [ALPH.nanoAlph(10000000)]
      val updatedAmount = ALPH
        .nanoAlph(897950000000L)
        .addUnsafe(ALPH.alph(100))
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(997940000000L)

      currentUTXOs is Set(
        (ALPH.nanoAlph(997940000000L), token(24)),
        (ALPH.alph(10000), noTokens),
        (ALPH.alph(100000), noTokens),
        (ALPH.nanoAlph(888899997244000L), noTokens)
      )
    }

    eventually {
      request[Balance](getBalance(address), restPort) isnot initialBalance
    }
    selfClique.nodes.foreach { peer => request[Boolean](stopMining, peer.restPort) is true }
    clique.stop()
  }
}

object SwapContracts {
  val tokenContract = s"""
                         |TxContract Token(mut x: U256) {
                         |
                         |  @using(assetsInContract = true)
                         |  pub fn withdraw(address: Address, amount: U256) -> () {
                         |    transferTokenFromSelf!(address, selfTokenId!(), amount)
                         |  }
                         |}
    """.stripMargin

  def tokenWithdrawTxScript(address: String, tokenContractKey: Hash, tokenAmount: U256) =
    s"""
       |TxScript Main {
       |  let token = Token(#${tokenContractKey.toHexString})
       |  token.withdraw(@${address}, $tokenAmount)
       |}
       |
       |$tokenContract
       |""".stripMargin

  val swapContract =
    s"""
       |// Simple swap contract purely for testing
       |
       |TxContract Swap(tokenId: ByteVec, mut alphReserve: U256, mut tokenReserve: U256) {
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn addLiquidity(lp: Address, alphAmount: U256, tokenAmount: U256) -> () {
       |    transferAlphToSelf!(lp, alphAmount)
       |    transferTokenToSelf!(lp, tokenId, tokenAmount)
       |    alphReserve = alphAmount
       |    tokenReserve = tokenAmount
       |  }
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn swapToken(buyer: Address, alphAmount: U256) -> () {
       |    let tokenAmount = tokenReserve - alphReserve * tokenReserve / (alphReserve + alphAmount)
       |    transferAlphToSelf!(buyer, alphAmount)
       |    transferTokenFromSelf!(buyer, tokenId, tokenAmount)
       |    alphReserve = alphReserve + alphAmount
       |    tokenReserve = tokenReserve - tokenAmount
       |  }
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn swapAlph(buyer: Address, tokenAmount: U256) -> () {
       |    let alphAmount = alphReserve - alphReserve * tokenReserve / (tokenReserve + tokenAmount)
       |    transferTokenToSelf!(buyer, tokenId, tokenAmount)
       |    transferAlphFromSelf!(buyer, alphAmount)
       |    alphReserve = alphReserve - alphAmount
       |    tokenReserve = tokenReserve + tokenAmount
       |  }
       |}
       |""".stripMargin

  def addLiquidityTxScript(
      address: String,
      alphAmount: U256,
      tokenId: Hash,
      tokenAmount: U256,
      swapContractKey: Hash
  ) = s"""
         |TxScript Main {
         |  approveAlph!(@${address}, $alphAmount)
         |  approveToken!(@${address}, #${tokenId.toHexString}, $tokenAmount)
         |  let swap = Swap(#${swapContractKey.toHexString})
         |  swap.addLiquidity(@${address}, $alphAmount, $tokenAmount)
         |}
         |
         |$swapContract
         |""".stripMargin

  def swapTokenForAlphTxScript(
      address: String,
      swapContractKey: Hash,
      tokenId: Hash,
      tokenAmount: U256
  ) = s"""
         |TxScript Main {
         |  approveToken!(@${address}, #${tokenId.toHexString}, $tokenAmount)
         |  let swap = Swap(#${swapContractKey.toHexString})
         |  swap.swapAlph(@${address}, $tokenAmount)
         |}
         |
         |$swapContract
         |""".stripMargin

  def swapAlphForTokenTxScript(address: String, swapContractKey: Hash, alphAmount: U256) =
    s"""
       |TxScript Main {
       |  approveAlph!(@${address}, $alphAmount)
       |  let swap = Swap(#${swapContractKey.toHexString})
       |  swap.swapToken(@${address}, $alphAmount)
       |}
       |
       |$swapContract
       |""".stripMargin
}
