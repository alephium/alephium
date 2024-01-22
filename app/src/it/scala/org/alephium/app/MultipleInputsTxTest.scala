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

import org.alephium.api.model._
import org.alephium.protocol._
import org.alephium.protocol.model.Address
import org.alephium.util._

class MultipleInputsTxTest extends AlephiumActorSpec {
  it should "one input" in new CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()

    def currentUTXOs(addr: String) = {
      request[UTXOs](getUTXOs(addr), clique.masterRestPort)
    }

    val addressGroupIndex = Address.fromBase58(address).get.groupIndex

    val (address2, _, _) = generateAccount(addressGroupIndex)

    clique.startMining()

    val utxos = currentUTXOs(address).utxos

    val amount = utxos.map(_.amount.value).fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.Two)

    val destAmount = amount

    val destinations = AVector(
      Destination(Address.asset(address2).get, Amount(destAmount))
    )

    val inputs: AVector[BuildMultiAddressesTransaction.Source] = AVector(
      BuildMultiAddressesTransaction.Source(pubKey(publicKey).bytes, destinations)
    )

    val genericTx = transferGeneric(
      inputs,
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
      request[UTXOs](getUTXOs(addr), clique.masterRestPort)
    }

    val addressGroupIndex = Address.fromBase58(address).get.groupIndex

    val (address2, publicKey2, privateKey2) = generateAccount(addressGroupIndex)
    val (address3, publicKey3, privateKey3) = generateAccount(addressGroupIndex)
    val (address4, publicKey4, privateKey4) = generateAccount(addressGroupIndex)
    val (destAddress, _, _)                 = generateAccount(addressGroupIndex)

    val tx =
      transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)
    val tx2 =
      transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)
    val tx3 =
      transfer(publicKey, address3, transferAmount, privateKey, clique.masterRestPort)
    val tx4 =
      transfer(publicKey, address4, transferAmount, privateKey, clique.masterRestPort)

    clique.startMining()
    confirmTx(tx, clique.masterRestPort)
    confirmTx(tx2, clique.masterRestPort)
    confirmTx(tx3, clique.masterRestPort)
    confirmTx(tx4, clique.masterRestPort)

    val utxos = currentUTXOs(address).utxos

    val amount = utxos.map(_.amount.value).fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.Two)
    // Force Two utxos
    val amount2 = transferAmount.mulUnsafe(U256.Two) - transferAmount.divUnsafe(U256.Two)

    val utxos3  = currentUTXOs(address3).utxos
    val amount3 = utxos3.map(_.amount.value).fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.Two)

    val utxos4  = currentUTXOs(address4).utxos
    val amount4 = utxos4.map(_.amount.value).fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.Two)

    def destinations(
        amount: U256,
        lockTime: Option[TimeStamp] = None,
        message: Option[ByteString] = None
    ) = AVector(
      Destination(
        Address.asset(destAddress).get,
        Amount(amount),
        lockTime = lockTime,
        message = message
      )
    )

    val inputs: AVector[BuildMultiAddressesTransaction.Source] = AVector(
      BuildMultiAddressesTransaction.Source(pubKey(publicKey).bytes, destinations(amount)),
      BuildMultiAddressesTransaction.Source(pubKey(publicKey2).bytes, destinations(amount2)),
      BuildMultiAddressesTransaction
        .Source(pubKey(publicKey3).bytes, destinations(amount3, lockTime = Some(TimeStamp.now()))),
      BuildMultiAddressesTransaction.Source(
        pubKey(publicKey4).bytes,
        destinations(amount4, message = Some(ByteString.empty))
      )
    )

    val genericTx = transferGeneric(
      inputs,
      AVector(privateKey, privateKey2, privateKey3, privateKey4),
      clique.masterRestPort
    )

    confirmTx(genericTx, clique.masterRestPort)

    clique.stopMining()

    // not only 1 utoxs as there are input with locktime and message
    currentUTXOs(destAddress).utxos.length is 3

    clique.stop()
  }

  def pubKey(str: String): PublicKey = PublicKey.unsafe(Hex.unsafe(str))
}
