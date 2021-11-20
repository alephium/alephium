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

import org.scalatest.Assertion
import sttp.model.StatusCode

import org.alephium.api.model._
import org.alephium.flow.core.UtxoUtils
import org.alephium.json.Json._
import org.alephium.protocol.{ALPH, Hash, PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util._

class SmartContractTest extends AlephiumActorSpec {

  it should "compile contract failed when have invalid state length" in new CliqueFixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))
    val clique                = bootClique(1)
    clique.start()

    val restPort = clique.masterRestPort
    val contract =
      s"""
         |TxContract Foo() {
         |  pub fn foo() -> () {
         |    return
         |  }
         |}
         |""".stripMargin

    val compileResult = request[CompileResult](compileContract(contract), restPort)
    unitRequest(
      buildContract(publicKey, compileResult.code),
      restPort
    )

    val invalidState: Option[String] = Some("[1000u]")
    requestFailed(
      buildContract(publicKey, compileResult.code, state = invalidState),
      restPort,
      StatusCode.BadRequest
    )

    clique.stop()
  }

  it should "compile/execute smart contracts" in new CliqueFixture {

    val clique = bootClique(nbOfNodes = 2)
    clique.start()

    val selfClique = clique.selfClique()
    val group      = request[Group](getGroup(address), clique.masterRestPort)
    val index      = group.group % selfClique.brokerNum
    val restPort   = selfClique.nodes(index).restPort

    def checkUTXOs(check: Set[(Hash, U256)] => Assertion) = {
      val currentUTXOs = request[UTXOs](getUTXOs(address), restPort)
      check {
        currentUTXOs.utxos.map { utxo =>
          (utxo.ref.key, utxo.amount.value)
        }.toSet
      }
    }

    def contract(
        code: String,
        state: Option[String],
        issueTokenAmount: Option[U256],
        gas: Option[Int] = Some(100000),
        gasPrice: Option[GasPrice] = None
    ): BuildContractResult = {
      val compileResult = request[CompileResult](compileContract(code), restPort)
      val buildResult = request[BuildContractResult](
        buildContract(
          fromPublicKey = publicKey,
          code = compileResult.code,
          gas,
          gasPrice,
          state = state,
          issueTokenAmount = issueTokenAmount
        ),
        restPort
      )
      submitTx(buildResult.unsignedTx, buildResult.hash)
      buildResult
    }

    def submitTx(unsignedTx: String, txId: Hash): Hash = {
      val signature: Signature =
        SignatureSchema.sign(txId.bytes, PrivateKey.unsafe(Hex.unsafe(privateKey)))
      val txResult = request[TxResult](
        submitTransaction(s"""
          {
            "unsignedTx": "$unsignedTx",
            "signature":"${signature.toHexString}"
          }"""),
        restPort
      )
      confirmTx(txResult, restPort)
      txResult.txId
    }

    def script(
        code: String,
        gas: Option[Int] = Some(100000),
        gasPrice: Option[GasPrice] = None
    ) = {
      val compileResult = request[CompileResult](compileScript(code), restPort)
      val buildResult = request[BuildScriptResult](
        buildScript(
          fromPublicKey = publicKey,
          code = compileResult.code,
          gas,
          gasPrice
        ),
        restPort
      )
      submitTx(buildResult.unsignedTx, buildResult.hash)
      buildResult
    }

    def hash(str: String): Hash = {
      Hash.from(Hex.unsafe(str)).value
    }

    def decodeTx(str: String): Tx = {
      request[Tx](decodeUnsignedTransaction(str), restPort)
    }

    def verifyPickedUTXOs(unsignedTx: String, hashes: Set[Hash]) = {
      val tx = decodeTx(unsignedTx)
      tx.inputs.map(_.outputRef.key).toSet is hashes
    }

    request[Balance](getBalance(address), restPort) is initialBalance
    startWS(defaultWsMasterPort)

    selfClique.nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }

    request[Balance](getBalance(address), restPort) is initialBalance

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
        .subUnsafe(defaultGasPrice * UtxoUtils.estimateGas(1, 5))

      changeAmount is ALPH.nanoAlph(888899996750000L)

      // format: off
      currentUTXOs is Set(
        (hash("72ee610a89ceb6651c7e76c920db00de873af09ccbf2bd045ca8267f505ae31f"), ALPH.alph(100)),
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    info("Create token contract")
    val tokenContract = s"""
      |TxContract Token(mut x: U256) {
      |
      | pub payable fn withdraw(address: Address, amount: U256) -> () {
      |   transferTokenFromSelf!(address, selfTokenId!(), amount)
      | }
      |}
      """.stripMargin

    val tokenContractBuildResult =
      contract(
        tokenContract,
        gas = Some(100000),
        state = Some("[0u]"),
        issueTokenAmount = Some(1024)
      )
    val tokenContractKey = tokenContractBuildResult.contractId

    verifyPickedUTXOs(
      tokenContractBuildResult.unsignedTx,
      Set(hash("72ee610a89ceb6651c7e76c920db00de873af09ccbf2bd045ca8267f505ae31f"))
    )

    // When creating a token contract, by default
    //   - dustUtxoAmount of ALPH is transfered (ALPH.nanoAlph(1000))
    //   - Gas fee: defaultGasPrice  * 100000   (ALPH.nanoAlph(10000000))
    //   - Total: ALPH.nanoAlph(10001000)
    checkUTXOs { currentUTXOs =>
      val updatedAmount = ALPH
        .alph(100)
        .subUnsafe(dustUtxoAmount)
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(99989999000L)

      // format: off
      currentUTXOs is Set(
        (hash("a05f1d268acd0bdf87c728b36119b2b45c778a34179ba2dcea7d16600d01e99b"), ALPH.nanoAlph(99989999000L)),
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    info("Transfer 1024 token back to self")
    val transferTokenScriptResult = script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    let token = Token(#${tokenContractKey.toHexString})
      |    token.withdraw(@${address}, 1024)
      |  }
      |}
      |
      |$tokenContract
      |""".stripMargin)

    verifyPickedUTXOs(
      transferTokenScriptResult.unsignedTx,
      Set(hash("a05f1d268acd0bdf87c728b36119b2b45c778a34179ba2dcea7d16600d01e99b"))
    )

    // When creating a script contract, by default
    //   - 0 ALPH is transfered
    //   - Gas fee: defaultGasPrice  * 100000   (ALPH.nanoAlph(10000000))
    //   - Total: ALPH.nanoAlph(10001000)
    checkUTXOs { currentUTXOs =>
      val updatedAmount = ALPH
        .nanoAlph(99989999000L)
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(99979999000L)

      // format: off
      currentUTXOs is Set(
        (hash("de02d79066ba0138a9f19312e94bf0205845b79733b56185c5c6149ee6bad83b"), ALPH.nanoAlph(99979999000L)), // TODO: check token
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    val swapContract = s"""
      |// Simple swap contract purely for testing
      |
      |TxContract Swap(tokenId: ByteVec, mut alphReserve: U256, mut tokenReserve: U256) {
      |
      |  pub payable fn addLiquidity(lp: Address, alphAmount: U256, tokenAmount: U256) -> () {
      |    transferAlphToSelf!(lp, alphAmount)
      |    transferTokenToSelf!(lp, tokenId, tokenAmount)
      |    alphReserve = alphAmount
      |    tokenReserve = tokenAmount
      |  }
      |
      |  pub payable fn swapToken(buyer: Address, alphAmount: U256) -> () {
      |    let tokenAmount = tokenReserve - alphReserve * tokenReserve / (alphReserve + alphAmount)
      |    transferAlphToSelf!(buyer, alphAmount)
      |    transferTokenFromSelf!(buyer, tokenId, tokenAmount)
      |    alphReserve = alphReserve + alphAmount
      |    tokenReserve = tokenReserve - tokenAmount
      |  }
      |
      |  pub payable fn swapAlph(buyer: Address, tokenAmount: U256) -> () {
      |    let alphAmount = alphReserve - alphReserve * tokenReserve / (tokenReserve + tokenAmount)
      |    transferTokenToSelf!(buyer, tokenId, tokenAmount)
      |    transferAlphFromSelf!(buyer, alphAmount)
      |    alphReserve = alphReserve - alphAmount
      |    tokenReserve = tokenReserve + tokenAmount
      |  }
      |}
      |""".stripMargin

    val swapContractBuildResult = contract(
      swapContract,
      Some(s"[#${tokenContractKey.toHexString},0,0]"),
      issueTokenAmount = Some(10000)
    )
    val swapContractKey = swapContractBuildResult.contractId

    verifyPickedUTXOs(
      swapContractBuildResult.unsignedTx,
      Set(hash("de02d79066ba0138a9f19312e94bf0205845b79733b56185c5c6149ee6bad83b"))
    )

    // When creating a token contract, by default
    //   - dustUtxoAmount of ALPH is transfered (ALPH.nanoAlph(1000))
    //   - Gas fee: defaultGasPrice  * 100000   (ALPH.nanoAlph(10000000))
    //   - Total: ALPH.nanoAlph(10001000)
    checkUTXOs { currentUTXOs =>
      val updatedAmount = ALPH
        .nanoAlph(99979999000L)
        .subUnsafe(dustUtxoAmount)
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(99969998000L)

      // format: off
      currentUTXOs is Set(
        (hash("8a28d6a00e58d5cab36b94b12119da550351f71a530842a0d7ca712633cc0b9d"), ALPH.nanoAlph(99969998000L)),
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    val addLiquidityScriptBuildResult = script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveAlph!(@${address}, 10000000000000000000)  // 10 ALPH
      |    approveToken!(@${address}, #${tokenContractKey.toHexString}, 100)
      |    let swap = Swap(#${swapContractKey.toHexString})
      |    swap.addLiquidity(@${address}, 10000000000000000000, 100)
      |  }
      |}
      |
      |$swapContract
      |""".stripMargin)

    verifyPickedUTXOs(
      addLiquidityScriptBuildResult.unsignedTx,
      Set(hash("8a28d6a00e58d5cab36b94b12119da550351f71a530842a0d7ca712633cc0b9d"))
    )

    // When creating a token contract, by default
    //   - 10 of ALPH is received (ALPH.alph(10))
    //   - 100 of token is sent
    //   - Gas fee: defaultGasPrice  * 100000   (ALPH.nanoAlph(10000000))
    checkUTXOs { currentUTXOs =>
      val updatedAmount = ALPH
        .nanoAlph(99969998000L)
        .subUnsafe(ALPH.alph(10))
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(89959998000L)

      // format: off
      currentUTXOs is Set(
        (hash("a4d40df75a5e74781bceb2fbafb15a49e5e6d704184086bbb0abca68447a77df"), ALPH.nanoAlph(89959998000L)),
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    val swapTokenScriptBuildResult = script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveAlph!(@${address}, 10000000000000000000)
      |    let swap = Swap(#${swapContractKey.toHexString})
      |    swap.swapToken(@${address}, 10000000000000000000)
      |  }
      |}
      |
      |$swapContract
      |""".stripMargin)

    verifyPickedUTXOs(
      swapTokenScriptBuildResult.unsignedTx,
      Set(hash("a4d40df75a5e74781bceb2fbafb15a49e5e6d704184086bbb0abca68447a77df"))
    )

    // When creating a token contract, by default
    //   - 10 of ALPH is sent (ALPH.alph(10))
    //   - Gas fee: defaultGasPrice  * 100000   (ALPH.nanoAlph(10000000))
    checkUTXOs { currentUTXOs =>
      val updatedAmount = ALPH
        .nanoAlph(89959998000L)
        .subUnsafe(ALPH.alph(10))
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(79949998000L)

      // format: off
      currentUTXOs is Set(
        (hash("36dda83774354f5021823f13c92e070f03966a91534b99ef5aec4c007066451b"), ALPH.nanoAlph(79949998000L)),  // TODO: check token
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    val swapAlphScriptBuildResult = script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveToken!(@${address}, #${tokenContractKey.toHexString}, 50)
      |    let swap = Swap(#${swapContractKey.toHexString})
      |    swap.swapAlph(@${address}, 50)
      |  }
      |}
      |
      |$swapContract
      |""".stripMargin)

    verifyPickedUTXOs(
      swapAlphScriptBuildResult.unsignedTx,
      Set(hash("36dda83774354f5021823f13c92e070f03966a91534b99ef5aec4c007066451b"))
    )

    // When creating a token contract, by default
    //   - 10 of ALPH is received (ALPH.alph(10))
    //   - 50 of token is sent
    //   - Gas fee: defaultGasPrice  * 100000   (ALPH.nanoAlph(10000000))
    checkUTXOs { currentUTXOs =>
      val updatedAmount = ALPH
        .nanoAlph(79949998000L)
        .addUnsafe(ALPH.alph(10))
        .subUnsafe(defaultGasPrice * GasBox.unsafe(100000))

      updatedAmount is ALPH.nanoAlph(89939998000L)

      // format: off
      currentUTXOs is Set(
        (hash("896382d22bdb6d7dcde09ec71f19f7a25e5144f55048e2b4646d327940d10a5e"), ALPH.nanoAlph(89939998000L)),  // TODO: check token
        (hash("0b1556276bbde60fb6b09e2b3e4c6b7274c37e26f2f38392be782acbb81bfced"), ALPH.alph(1000)),
        (hash("691a0abe3bf0fecdb4e2c9e1e6e14d7e78bc887993e3cab0c7ca58794b7b86a3"), ALPH.alph(10000)),
        (hash("12ee7a584bc9c5413f79e378a825a544de13a1878b9c45dfb2511f90a0f4a042"), ALPH.alph(100000)),
        (hash("dee2a1631f6d84604f5933ee90325180fe392c5e71757d38d1e2eebf52108f00"), ALPH.nanoAlph(888899996750000L))
      )
      // format: on
    }

    eventually {
      request[Balance](getBalance(address), restPort) isnot initialBalance
    }

    selfClique.nodes.foreach { peer => request[Boolean](stopMining, peer.restPort) is true }
    clique.stop()
  }
}
