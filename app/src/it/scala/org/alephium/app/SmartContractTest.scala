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

import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol.{Hash, PrivateKey, Signature, SignatureSchema}
import org.alephium.util._

class SmartContractTest extends AlephiumActorSpec {

  it should "compile/execute smart contracts" in new CliqueFixture {

    val clique = bootClique(nbOfNodes = 2)
    clique.start()

    val selfClique = clique.selfClique()
    val group      = request[Group](getGroup(address), clique.masterRestPort)
    val index      = group.group % selfClique.brokerNum
    val restPort   = selfClique.nodes(index).restPort

    def contract(
        code: String,
        state: Option[String] = None,
        issueTokenAmount: Option[U256]
    ): Hash = {
      val compileResult = request[CompileResult](compileContract(code), restPort)
      val buildResult = request[BuildContractResult](
        buildContract(
          fromPublicKey = publicKey,
          code = compileResult.code,
          state = state,
          issueTokenAmount = issueTokenAmount
        ),
        restPort
      )
      submitTx(compileResult.code, buildResult.unsignedTx, buildResult.hash)
      buildResult.contractId
    }

    def submitTx(code: String, unsignedTx: String, txId: Hash) = {
      val signature: Signature =
        SignatureSchema.sign(txId.bytes, PrivateKey.unsafe(Hex.unsafe(privateKey)))
      val tx = request[TxResult](
        submitContract(s"""
          {
            "tx": "$unsignedTx",
            "code": "$code",
            "fromGroup": ${group.group},
            "signature":"${signature.toHexString}"
          }"""),
        restPort
      )
      confirmTx(tx, restPort)
    }

    def script(code: String) = {
      val compileResult = request[CompileResult](compileScript(code), restPort)
      val buildResult = request[BuildScriptResult](
        buildScript(
          fromPublicKey = publicKey,
          code = compileResult.code
        ),
        restPort
      )
      submitTx(compileResult.code, buildResult.unsignedTx, buildResult.hash)
    }

    request[Balance](getBalance(address), restPort) is initialBalance
    startWS(defaultWsMasterPort)

    selfClique.nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }

    val tokenContract = s"""
      |TxContract Token(mut x: U256) {
      |
      | pub payable fn withdraw(address: Address, amount: U256) -> () {
      |   transferTokenFromSelf!(address, selfTokenId!(), amount)
      | }
      |}
      """.stripMargin

    val tokenContractKey = contract(tokenContract, issueTokenAmount = Some(1024))

    script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    let token = Token(#${tokenContractKey.toHexString})
      |    token.withdraw(@${address}, 1024)
      |  }
      |}
      |
      |$tokenContract
      |""".stripMargin)

    val swapContract = s"""
      |// Simple swap contract purely for testing
      |
      |TxContract Swap(tokenId: ByteVec, mut alfReserve: U256, mut tokenReserve: U256) {
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

    val swapContractKey = contract(
      swapContract,
      Some(s"[#${tokenContractKey.toHexString},0,0]"),
      issueTokenAmount = Some(10000)
    )

    script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveAlf!(@${address}, 10)
      |    approveToken!(@${address}, #${tokenContractKey.toHexString}, 100)
      |    let swap = Swap(#${swapContractKey.toHexString})
      |    swap.addLiquidity(@${address}, 10, 100)
      |  }
      |}
      |
      |$swapContract
      |""".stripMargin)

    script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveAlf!(@${address}, 10)
      |    let swap = Swap(#${swapContractKey.toHexString})
      |    swap.swapToken(@${address}, 10)
      |  }
      |}
      |
      |$swapContract
      |""".stripMargin)

    script(s"""
      |TxScript Main {
      |  pub payable fn main() -> () {
      |    approveToken!(@${address}, #${tokenContractKey.toHexString}, 50)
      |    let swap = Swap(#${swapContractKey.toHexString})
      |    swap.swapAlf(@${address}, 50)
      |  }
      |}
      |
      |$swapContract
      |""".stripMargin)

    eventually {
      request[Balance](getBalance(address), restPort) isnot initialBalance
    }

    selfClique.nodes.foreach { peer => request[Boolean](stopMining, peer.restPort) is true }
    clique.stop()
  }
}
