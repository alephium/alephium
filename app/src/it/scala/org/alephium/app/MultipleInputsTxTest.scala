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
import org.alephium.protocol.model.Address
import org.alephium.util._

class MultipleInputsTxTest extends AlephiumActorSpec {
  it should "one input" in new CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    def currentUTXOs(addr: String) = {
      val utxos = request[UTXOs](getUTXOs(addr), clique.masterRestPort)
      utxos
    }

    info("Transfering to one p2pkh address and one p2mpkh to test the generic build tx")
    val (address2, _, _) = generateAccount(addressGroupIndex)

    clique.startMining()

    info("Building and sending actual generic tx")
    val utxos = currentUTXOs(address).utxos

    // val totalAmount = (utxos ++ utxos2).map(_.amount.value).fold(U256.Zero)(_ addUnsafe _)

    val amount = utxos.map(_.amount.value).fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.Two)

    val inputs: AVector[BuildMultiInputsTransaction.Source] = AVector(
      BuildMultiInputsTransaction.Source(pubKey(publicKey).bytes, Amount(amount))
    )

    val destAmount = amount

    val destinations = AVector(
      Destination(Address.asset(address2).get, Amount(destAmount))
    )

    val genericTx = transferGeneric(
      inputs,
      destinations,
      None,
      AVector(privateKey),
      clique.masterRestPort
    )

    confirmTx(genericTx, clique.masterRestPort)

    clique.stopMining()

    currentUTXOs(address2).utxos.length is 1

    clique.stop()
  }

  it should "build a generic tx" in new CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    def currentUTXOs(addr: String) = {
      val utxos = request[UTXOs](getUTXOs(addr), clique.masterRestPort)
      utxos
    }

    info("Transfering to one p2pkh address and one p2mpkh to test the generic build tx")
    val (address2, publicKey2, privateKey2) = generateAccount(addressGroupIndex)
    val (address3, _, _)                    = generateAccount(addressGroupIndex)

    val tx =
      transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)
    val tx2 =
      transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)

    clique.startMining()
    confirmTx(tx, clique.masterRestPort)
    confirmTx(tx2, clique.masterRestPort)

    info("Building and sending actual generic tx")
    val utxos  = currentUTXOs(address).utxos
    //val utxos2 = currentUTXOs(address2).utxos

    // val totalAmount = (utxos ++ utxos2).map(_.amount.value).fold(U256.Zero)(_ addUnsafe _)

    val amount = utxos.map(_.amount.value).fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.Two)
    // Force Two utxos
    val amount2 = transferAmount.mulUnsafe(U256.Two) - transferAmount.divUnsafe(U256.Two)

    val inputs: AVector[BuildMultiInputsTransaction.Source] = AVector(
      BuildMultiInputsTransaction.Source(pubKey(publicKey).bytes, Amount(amount)),
      BuildMultiInputsTransaction.Source(pubKey(publicKey2).bytes, Amount(amount2))
    )

    val destAmount = amount.addUnsafe(amount2)

    val destinations = AVector(
      Destination(Address.asset(address3).get, Amount(destAmount))
    )

    val genericTx = transferGeneric(
      inputs,
      destinations,
      None,
      AVector(privateKey, privateKey2),
      clique.masterRestPort
    )

    confirmTx(genericTx, clique.masterRestPort)

    clique.stopMining()

    currentUTXOs(address3).utxos.length is 1

    clique.stop()
  }

  def pubKey(str: String): PublicKey = PublicKey.unsafe(Hex.unsafe(str))
}
