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
import org.alephium.protocol._
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasPrice
import org.alephium.serde._
import org.alephium.util._
import org.alephium.wallet.api.model.{Addresses, AddressInfo, WalletCreationResult}

class MultisigSmartContractTest extends AlephiumActorSpec {
  it should "build multisig DeployContractTx" in new MultisigSmartContractFixture {
    contract(
      SwapContracts.tokenContract,
      gas = Some(100000),
      initialFields = None,
      issueTokenAmount = Some(1024)
    )
    clique.stopMining()
    clique.stop()
  }

  it should "compile/execute the multisig swap contracts successfully" in new MultisigSmartContractFixture {
    info("Create token contract")
    val tokenContractBuildResult = contract(
      SwapContracts.tokenContract,
      gas = Some(100000),
      initialFields = None,
      issueTokenAmount = Some(1024)
    )
    val tokenContractId = tokenContractBuildResult.contractAddress.contractId
    val tokenId         = TokenId.from(tokenContractId)

    info("Transfer 1024 token back to self")
    script(SwapContracts.tokenWithdrawTxScript(multisigAddress, tokenContractId, U256.unsafe(1024)))

    info("Create the ALPH/token swap contract")
    val swapContractBuildResult = contract(
      SwapContracts.swapContract,
      initialFields = Some(
        AVector[vm.Val](
          vm.Val.ByteVec(tokenContractId.bytes),
          vm.Val.U256.unsafe(1),
          vm.Val.U256.unsafe(1)
        )
      ),
      issueTokenAmount = Some(10000)
    )
    val swapContractKey = swapContractBuildResult.contractAddress.contractId

    info("Add liquidity to the swap contract")
    script(
      SwapContracts.addLiquidityTxScript(
        multisigAddress,
        ALPH.alph(1),
        tokenId,
        U256.unsafe(100),
        swapContractKey
      )
    )

    info("Swap ALPH with tokens")
    script(SwapContracts.swapAlphForTokenTxScript(multisigAddress, swapContractKey, ALPH.alph(1)))

    info("Swap tokens with ALPH")
    script(
      SwapContracts.swapTokenForAlphTxScript(
        multisigAddress,
        swapContractKey,
        tokenId,
        U256.unsafe(500)
      )
    )

    eventually {
      request[Balance](getBalance(multisigAddress), restPort).tokenBalances is Some(
        AVector(Token(tokenId, U256.unsafe(474)))
      )
    }

    clique.stopMining()
    clique.stop()
  }
}

trait MultisigSmartContractFixture extends CliqueFixture {
  val clique = bootClique(nbOfNodes = 1)
  clique.start()
  clique.startWs()

  val group    = request[Group](getGroup(address), clique.masterRestPort)
  val restPort = clique.getRestPort(group.group)

  request[Balance](getBalance(address), restPort) is initialBalance
  clique.startMining()

  val walletName = "wallet-name"
  request[WalletCreationResult](createWallet(password, walletName), restPort)

  val address2 =
    request[Addresses](getAddresses(walletName), restPort).activeAddress

  val publicKey2 =
    request[AddressInfo](
      getAddressInfo(walletName, address2.toBase58),
      restPort
    ).publicKey.toHexString

  val (address3, publicKey3, privateKey3) = generateAccount

  val multisigAddress =
    request[BuildMultisigAddressResult](
      multisig(AVector(publicKey, publicKey2, publicKey3), 2),
      restPort
    ).address.toBase58

  val tx = transfer(publicKey, multisigAddress, transferAmount * 200, privateKey, restPort)
  confirmTx(tx, restPort)

  def contract(
      code: String,
      gas: Option[Int] = Some(100000),
      gasPrice: Option[GasPrice] = None,
      initialFields: Option[AVector[vm.Val]] = None,
      issueTokenAmount: Option[U256] = None
  ) = {
    val buildResult = buildMultisigDeployContractTxWithPort(
      multisigAddress,
      AVector(publicKey, publicKey3),
      code,
      restPort,
      gas,
      gasPrice,
      initialFields,
      issueTokenAmount
    )
    submitMultisigTx(buildResult.unsignedTx, buildResult.txId)
    buildResult
  }

  def script(
      code: String,
      attoAlphAmount: Option[Amount] = None,
      gas: Option[Int] = Some(100000),
      gasPrice: Option[GasPrice] = None
  ): BuildExecuteScriptTxResult = {

    val buildResult = buildMultisigExecuteScriptTxWithPort(
      multisigAddress,
      AVector(publicKey, publicKey3),
      code,
      restPort,
      attoAlphAmount,
      gas,
      gasPrice
    )
    submitMultisigTx(buildResult.unsignedTx, buildResult.txId)
    buildResult
  }

  def submitMultisigTx(unsignedTx: String, txId: TransactionId): TransactionId = {
    val txResult = request[SubmitTxResult](
      signMultisigTx(unsignedTx, txId),
      restPort
    )
    confirmTx(txResult, restPort)
    txResult.txId
  }

  def signMultisigTx(unsignedTxStr: String, txId: TransactionId) = {
    val unsignedTx =
      deserialize[UnsignedTransaction](Hex.from(unsignedTxStr).get).rightValue
    val signature: Signature = SignatureSchema.sign(
      unsignedTx.id,
      PrivateKey.unsafe(Hex.unsafe(privateKey))
    )

    request[Boolean](
      verify(txId.toHexString, signature, publicKey),
      restPort
    ) is true

    val signature3: Signature = SignatureSchema.sign(
      unsignedTx.id,
      PrivateKey.unsafe(Hex.unsafe(privateKey3))
    )

    request[Boolean](
      verify(txId.toHexString, signature3, publicKey3),
      restPort
    ) is true

    submitMultisigTransaction(unsignedTxStr, AVector(signature, signature3))
  }
}
