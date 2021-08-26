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
import org.alephium.json.Json._
import org.alephium.protocol.model.{defaultGasFee, UnsignedTransaction}
import org.alephium.serde.deserialize
import org.alephium.util._

class MultisigTest extends AlephiumActorSpec {

  it should "handle multisig" in new CliqueFixture {

    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    val selfClique = clique.selfClique()
    val group      = request[Group](getGroup(address), clique.masterRestPort)
    val index      = group.group / selfClique.groupNumPerBroker
    val restPort   = selfClique.nodes(index).restPort

    val (_, publicKey2, _)           = generateAccount
    val (_, publicKey3, privateKey3) = generateAccount

    val multisigAddress =
      request[BuildMultisigAddress.Result](
        multisig(AVector(publicKey, publicKey2, publicKey3), 2),
        restPort
      )

    request[Balance](getBalance(multisigAddress.address.toBase58), restPort) is
      Balance(U256.Zero, 0, 0)

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
    ).detail is "Failed in adding transaction"

    val submitMultisigTx1sig = submitMultisigTransaction(buildTxResult, AVector(privateKey))
    request[ApiError.InternalServerError](
      submitMultisigTx1sig,
      restPort
    ).detail is "Failed in adding transaction"

    val submitMultisigTx =
      submitMultisigTransaction(buildTxResult, AVector(privateKey, privateKey3))
    val multisigTx = request[TxResult](submitMultisigTx, restPort)

    confirmTx(multisigTx, restPort)

    request[Balance](getBalance(multisigAddress.address.toBase58), restPort) is
      Balance(transferAmount.mulUnsafe(2) - amount - defaultGasFee, 0, 1)

    clique.stopMining()
    clique.stop()
  }
}
