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
import org.alephium.protocol.model.{nonCoinbaseMinGasPrice, Address}
import org.alephium.protocol.vm.GasBox
import org.alephium.util._

class GenericTxTest extends AlephiumActorSpec {
  it should "build a generic tx" in new CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    def currentUTXOs(addr: String) = {
      val utxos = request[UTXOs](getUTXOs(addr), clique.masterRestPort)
      utxos
    }

    info("Transfering to one p2pkh address and one p2mpkh to test the generic build tx")
    val (_, publicKey2, privateKey2) = generateAccount(addressGroupIndex)
    val (_, publicKey3, privateKey3) = generateAccount(addressGroupIndex)

    val multisigAddress =
      request[BuildMultisigAddressResult](
        multisig(AVector(publicKey2, publicKey3), 2),
        clique.masterRestPort
      ).address.toBase58

    val (address4, publicKey4, privateKey4) = generateAccount(addressGroupIndex)

    val tx =
      transfer(publicKey, address4, transferAmount, privateKey, clique.masterRestPort)

    val tx2 =
      transfer(publicKey, multisigAddress, transferAmount, privateKey, clique.masterRestPort)

    clique.startMining()
    confirmTx(tx, clique.masterRestPort)
    confirmTx(tx2, clique.masterRestPort)

    info("Building and sending actual generic tx")
    val utxos  = currentUTXOs(address).utxos
    val utxos2 = currentUTXOs(multisigAddress).utxos
    val utxos3 = currentUTXOs(address4).utxos

    val totalAmount = (utxos ++ utxos2 ++ utxos3).map(_.amount.value).fold(U256.Zero)(_ addUnsafe _)

    val outputRefs  = utxos.map(_.ref)
    val outputRefs2 = utxos2.map(_.ref)
    val outputRefs3 = utxos3.map(_.ref)

    val inputs: AVector[BuildInputs] = AVector(
      P2PKHInputs(pubKey(publicKey), outputRefs),
      P2MPKHInputs(
        Address.asset(multisigAddress).get,
        AVector(pubKey(publicKey2), pubKey(publicKey3)),
        outputRefs2
      ),
      P2PKHInputs(pubKey(publicKey4), outputRefs3)
    )

    val halfAmount = totalAmount.divUnsafe(U256.Two)

    val gas      = 200000
    val gasPrice = nonCoinbaseMinGasPrice
    val gasFee   = gasPrice * GasBox.unsafe(gas)

    val destinations = AVector(
      Destination(Address.asset(address).get, Amount(halfAmount.subUnsafe(gasFee))),
      Destination(Address.asset(address4).get, Amount(halfAmount))
    )

    val genericTx = transferGeneric(
      inputs,
      destinations,
      gas,
      gasPrice,
      AVector(privateKey, privateKey2, privateKey3, privateKey4),
      clique.masterRestPort
    )

    confirmTx(genericTx, clique.masterRestPort)

    clique.stopMining()

    currentUTXOs(address).utxos.length is 1
    currentUTXOs(multisigAddress).utxos.length is 0
    currentUTXOs(address4).utxos.length is 1

    clique.stop()
  }

  def pubKey(str: String): PublicKey = PublicKey.unsafe(Hex.unsafe(str))
}
