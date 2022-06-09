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

import org.alephium.api.ApiError
import org.alephium.api.model.{TransactionTemplate => _, _}
import org.alephium.flow.gasestimation._
import org.alephium.flow.validation.{InvalidSignature, NotEnoughSignature}
import org.alephium.json.Json._
import org.alephium.protocol.{Hash, PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasBox
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._
import org.alephium.wallet.api.model._

class MultisigTest extends AlephiumActorSpec {

  it should "handle multisig with private keys" in new MultisigFixture {

    val (_, publicKey2, _)           = generateAccount
    val (_, publicKey3, privateKey3) = generateAccount

    val buildTxResult = createMultisigTransaction(
      AVector(publicKey, publicKey2, publicKey3),
      AVector(publicKey, publicKey3)
    )

    val unsignedTx =
      deserialize[UnsignedTransaction](Hex.from(buildTxResult.unsignedTx).get).rightValue

    unsignedTx.inputs.length is 2

    val decodedTx =
      request[DecodeUnsignedTxResult](decodeUnsignedTransaction(buildTxResult.unsignedTx), restPort)

    decodedTx.fromGroup is unsignedTx.fromGroup.value
    decodedTx.toGroup is unsignedTx.toGroup.value
    decodedTx.unsignedTx is UnsignedTx.fromProtocol(unsignedTx)

    val submitTx = submitTransaction(buildTxResult, privateKey)
    request[ApiError.InternalServerError](
      submitTx,
      restPort
    ).detail is s"Failed in validating tx ${buildTxResult.txId.toHexString} due to ${NotEnoughSignature}: ${Hex
        .toHexString(serialize(TransactionTemplate.from(unsignedTx, PrivateKey.unsafe(Hex.unsafe(privateKey)))))}"

    submitFailedMultisigTransaction(
      buildTxResult,
      AVector(privateKey)
    ) is s"Failed in validating tx ${buildTxResult.txId.toHexString} due to ${NotEnoughSignature}: ${Hex
        .toHexString(serialize(TransactionTemplate.from(unsignedTx, PrivateKey.unsafe(Hex.unsafe(privateKey)))))}"

    submitFailedMultisigTransaction(
      buildTxResult,
      AVector(privateKey3)
    ) is s"Failed in validating tx ${buildTxResult.txId.toHexString} due to ${InvalidSignature}: ${Hex
        .toHexString(serialize(TransactionTemplate.from(unsignedTx, PrivateKey.unsafe(Hex.unsafe(privateKey3)))))}"

    submitSuccessfulMultisigTransaction(buildTxResult, AVector(privateKey, privateKey3))

    verifyEstimatedGas(unsignedTx, GasBox.unsafe(22240))

    clique.stopMining()
    clique.stop()
  }

  it should "estimate gas for 1-of-3 multisig transaction correctly" in new MultisigFixture {
    val (_, publicKey2, _) = generateAccount
    val (_, publicKey3, _) = generateAccount

    val buildResult = createMultisigTransaction(
      AVector(publicKey, publicKey2, publicKey3),
      AVector(publicKey)
    )

    val unsignedTx = submitSuccessfulMultisigTransaction(buildResult, AVector(privateKey))

    verifyEstimatedGas(unsignedTx, GasBox.unsafe(20000))

    clique.stopMining()
    clique.stop()
  }

  it should "estimate gas for 2-of-3 multisig transaction correctly" in new MultisigFixture {
    val (_, publicKey2, _)           = generateAccount
    val (_, publicKey3, privateKey3) = generateAccount

    val buildResult = createMultisigTransaction(
      AVector(publicKey, publicKey2, publicKey3),
      AVector(publicKey, publicKey3)
    )

    val unsignedTx =
      submitSuccessfulMultisigTransaction(buildResult, AVector(privateKey, privateKey3))

    verifyEstimatedGas(unsignedTx, GasBox.unsafe(22240))

    clique.stopMining()
    clique.stop()
  }

  it should "estimate gas for 3-of-4 multisig transaction correctly" in new MultisigFixture {
    val (_, publicKey2, _)           = generateAccount
    val (_, publicKey3, privateKey3) = generateAccount
    val (_, publicKey4, privateKey4) = generateAccount

    val buildResult = createMultisigTransaction(
      AVector(publicKey, publicKey2, publicKey3, publicKey4),
      AVector(publicKey, publicKey3, publicKey4)
    )

    val unsignedTx = submitSuccessfulMultisigTransaction(
      buildResult,
      AVector(privateKey, privateKey3, privateKey4)
    )

    verifyEstimatedGas(unsignedTx, GasBox.unsafe(26360))

    clique.stopMining()
    clique.stop()
  }

  it should "handle multisig with the wallet" in new CliqueFixture {

    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    val group    = request[Group](getGroup(address), clique.masterRestPort)
    val restPort = clique.getRestPort(group.group)

    val walletName = "wallet-name"
    request[WalletCreationResult](createWallet(password, walletName), restPort)

    val address2 =
      request[Addresses](getAddresses(walletName), restPort).activeAddress

    val publicKey2 =
      request[AddressInfo](
        getAddressInfo(walletName, address2.toBase58),
        restPort
      ).publicKey.toHexString

    val multisigAddress =
      request[BuildMultisigAddressResult](
        multisig(AVector(publicKey, publicKey2), 2),
        restPort
      ).address.toBase58

    clique.startMining()

    val tx = transfer(publicKey, multisigAddress, transferAmount, privateKey, restPort)
    confirmTx(tx, restPort)

    val buildTx = buildMultisigTransaction(
      multisigAddress,
      AVector(publicKey, publicKey2),
      address,
      transferAmount / 2
    )

    val buildTxResult = request[BuildTransactionResult](buildTx, restPort)

    val unsignedTx =
      deserialize[UnsignedTransaction](Hex.from(buildTxResult.unsignedTx).get).rightValue

    val signature1: Signature = SignatureSchema.sign(
      unsignedTx.hash.bytes,
      PrivateKey.unsafe(Hex.unsafe(privateKey))
    )

    val signature2: Signature =
      request[SignResult](sign(walletName, buildTxResult.txId.toHexString), restPort).signature

    request[Boolean](
      verify(buildTxResult.txId.toHexString, signature1, publicKey),
      restPort
    ) is true

    request[Boolean](
      verify(buildTxResult.txId.toHexString, signature2, publicKey2),
      restPort
    ) is true

    request[Boolean](
      verify(buildTxResult.txId.toHexString, signature2, publicKey),
      restPort
    ) is false

    request[Boolean](
      verify(Hash.generate.toHexString, signature1, publicKey),
      restPort
    ) is false

    val submitMultisigTx =
      submitMultisigTransaction(buildTxResult, AVector(signature1, signature2))

    val multisigTx = request[SubmitTxResult](submitMultisigTx, restPort)

    confirmTx(multisigTx, restPort)

    request[Balance](getBalance(multisigAddress), restPort) is
      Balance.from(Amount(transferAmount / 2 - defaultGasFee), Amount.Zero, 1)

    clique.stopMining()
    clique.stop()
  }

  class MultisigFixture extends CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    val group    = clique.getGroup(address)
    val restPort = clique.getRestPort(group.group)

    request[Balance](getBalance(address), restPort) is initialBalance

    def createMultisigTransaction(
        allPubKeys: AVector[String],
        unlockPubKeys: AVector[String]
    ): BuildTransactionResult = {
      val multisigAddress =
        request[BuildMultisigAddressResult](
          multisig(allPubKeys, unlockPubKeys.length),
          restPort
        ).address.toBase58

      request[Balance](getBalance(multisigAddress), restPort) is
        Balance.from(Amount.Zero, Amount.Zero, 0)

      clique.startMining()

      val tx = transfer(publicKey, multisigAddress, transferAmount, privateKey, restPort)
      confirmTx(tx, restPort)

      val tx2 = transfer(publicKey, multisigAddress, transferAmount, privateKey, restPort)
      confirmTx(tx2, restPort)

      val amount = transferAmount + (transferAmount / 2) // To force 2 inputs

      val buildTx = buildMultisigTransaction(
        multisigAddress,
        unlockPubKeys,
        address,
        amount
      )

      request[BuildTransactionResult](buildTx, restPort)
    }

    def submitSuccessfulMultisigTransaction(
        buildTxResult: BuildTransactionResult,
        unlockPrivKeys: AVector[String]
    ): UnsignedTransaction = {
      val unsignedTx =
        deserialize[UnsignedTransaction](Hex.from(buildTxResult.unsignedTx).get).rightValue
      val decodedTx =
        request[DecodeUnsignedTxResult](
          decodeUnsignedTransaction(buildTxResult.unsignedTx),
          restPort
        )

      decodedTx.fromGroup is unsignedTx.fromGroup.value
      decodedTx.toGroup is unsignedTx.toGroup.value
      decodedTx.unsignedTx is UnsignedTx.fromProtocol(unsignedTx)

      val submitMultisigTx = signAndSubmitMultisigTransaction(buildTxResult, unlockPrivKeys)
      val multisigTx       = request[SubmitTxResult](submitMultisigTx, restPort)

      confirmTx(multisigTx, restPort)

      unsignedTx
    }

    def submitFailedMultisigTransaction(
        buildTxResult: BuildTransactionResult,
        unlockPrivKeys: AVector[String]
    ): String = {
      val failedTx =
        signAndSubmitMultisigTransaction(buildTxResult, unlockPrivKeys)

      request[ApiError.InternalServerError](
        failedTx,
        restPort
      ).detail
    }

    def verifyEstimatedGas(unsignedTx: UnsignedTransaction, gas: GasBox) = {
      val inputUnlockScripts = unsignedTx.inputs.map(_.unlockScript)
      val estimatedGas = GasEstimation
        .estimate(
          inputUnlockScripts,
          unsignedTx.fixedOutputs.length,
          AssetScriptGasEstimator.Mock
        )
        .rightValue
      unsignedTx.gasAmount is gas
      unsignedTx.gasAmount is estimatedGas
    }

  }
}
