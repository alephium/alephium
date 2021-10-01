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
import org.alephium.api.model._
import org.alephium.flow.validation.{InvalidSignature, NotEnoughSignature}
import org.alephium.json.Json._
import org.alephium.protocol.{PrivateKey, Signature, SignatureSchema}
import org.alephium.protocol.model._
import org.alephium.serde.{deserialize, serialize}
import org.alephium.util._
import org.alephium.wallet.api.model._

class MultisigTest extends AlephiumActorSpec {

  it should "handle multisig with private keys" in new CliqueFixture {

    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    val group    = clique.getGroup(address)
    val restPort = clique.getRestPort(group.group)

    request[Balance](getBalance(address), restPort) is initialBalance

    val (_, publicKey2, _)           = generateAccount
    val (_, publicKey3, privateKey3) = generateAccount

    val multisigAddress =
      request[BuildMultisigAddress.Result](
        multisig(AVector(publicKey, publicKey2, publicKey3), 2),
        restPort
      )

    request[Balance](getBalance(multisigAddress.address.toBase58), restPort) is
      Balance.from(Amount.Zero, Amount.Zero, 0)

    val tx =
      transfer(publicKey, multisigAddress.address.toBase58, transferAmount, privateKey, restPort)

    clique.startMining()
    confirmTx(tx, restPort)

    val tx2 =
      transfer(publicKey, multisigAddress.address.toBase58, transferAmount, privateKey, restPort)

    confirmTx(tx2, restPort)

    val amount = transferAmount + (transferAmount / 2) //To force 2 inputs

    val buildTx = buildMultisigTransaction(
      multisigAddress.address.toBase58,
      AVector(publicKey, publicKey3), //order needs to be respected
      address,
      amount
    )
    val buildTxResult = request[BuildTransactionResult](buildTx, restPort)

    val unsignedTx =
      deserialize[UnsignedTransaction](Hex.from(buildTxResult.unsignedTx).get).rightValue

    unsignedTx.inputs.length is 2

    val decodedTx =
      request[Tx](decodeUnsignedTransaction(buildTxResult.unsignedTx), restPort)

    decodedTx is Tx.from(unsignedTx)

    val submitTx = submitTransaction(buildTxResult, privateKey)
    request[ApiError.InternalServerError](
      submitTx,
      restPort
    ).detail is s"Failed in validating tx ${buildTxResult.txId.toHexString} due to ${NotEnoughSignature}: ${Hex
      .toHexString(serialize(TransactionTemplate.from(unsignedTx, PrivateKey.unsafe(Hex.unsafe(privateKey)))))}"

    val submitMultisigTx1sig = signAndSubmitMultisigTransaction(buildTxResult, AVector(privateKey))
    request[ApiError.InternalServerError](
      submitMultisigTx1sig,
      restPort
    ).detail is s"Failed in validating tx ${buildTxResult.txId.toHexString} due to ${NotEnoughSignature}: ${Hex
      .toHexString(serialize(TransactionTemplate.from(unsignedTx, PrivateKey.unsafe(Hex.unsafe(privateKey)))))}"

    val submitMultisigTxWrongSig =
      signAndSubmitMultisigTransaction(buildTxResult, AVector(privateKey3))
    request[ApiError.InternalServerError](
      submitMultisigTxWrongSig,
      restPort
    ).detail is s"Failed in validating tx ${buildTxResult.txId.toHexString} due to ${InvalidSignature}: ${Hex
      .toHexString(serialize(TransactionTemplate.from(unsignedTx, PrivateKey.unsafe(Hex.unsafe(privateKey3)))))}"

    val submitMultisigTx =
      signAndSubmitMultisigTransaction(buildTxResult, AVector(privateKey, privateKey3))
    val multisigTx = request[TxResult](submitMultisigTx, restPort)

    confirmTx(multisigTx, restPort)

    request[Balance](getBalance(multisigAddress.address.toBase58), restPort) is
      Balance.from(Amount(transferAmount.mulUnsafe(2) - amount - defaultGasFee), Amount.Zero, 1)

    clique.stopMining()
    clique.stop()
  }

  it should "handle multisig with the wallet" in new CliqueFixture {

    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    val group    = request[Group](getGroup(address), clique.masterRestPort)
    val restPort = clique.getRestPort(group.group)

    val walletName = "wallet-name"
    request[WalletCreation.Result](createWallet(password, walletName), restPort)

    val address2 =
      request[Addresses](getAddresses(walletName), restPort).activeAddress

    val publicKey2 =
      request[AddressInfo](
        getAddressInfo(walletName, address2.toBase58),
        restPort
      ).publicKey.toHexString

    val multisigAddress =
      request[BuildMultisigAddress.Result](
        multisig(AVector(publicKey, publicKey2), 2),
        restPort
      )

    val tx =
      transfer(publicKey, multisigAddress.address.toBase58, transferAmount, privateKey, restPort)

    clique.startMining()
    confirmTx(tx, restPort)

    val buildTx = buildMultisigTransaction(
      multisigAddress.address.toBase58,
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
      request[Sign.Result](sign(walletName, buildTxResult.txId.toHexString), restPort).signature

    val submitMultisigTx =
      submitMultisigTransaction(buildTxResult, AVector(signature1, signature2))

    val multisigTx = request[TxResult](submitMultisigTx, restPort)

    confirmTx(multisigTx, restPort)

    request[Balance](getBalance(multisigAddress.address.toBase58), restPort) is
      Balance.from(Amount(transferAmount / 2 - defaultGasFee), Amount.Zero, 1)

    clique.stopMining()
    clique.stop()
  }
}
