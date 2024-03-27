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

import org.alephium.api.model._
import org.alephium.protocol._
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.GasBox
import org.alephium.util._

class MultipleInputsTxTest extends AlephiumActorSpec {
  it should "one input" in new Fixture {
    test {
      val inputs: AVector[BuildMultiAddressesTransaction.Source] = AVector(
        BuildMultiAddressesTransaction.Source(pubKey(publicKey).bytes, destinations(amount))
      )

      val genericTx = transferGeneric(
        inputs,
        AVector(privateKey),
        clique.masterRestPort
      )

      confirmTx(genericTx, clique.masterRestPort)

      currentUTXOs(destAddress).utxos.length is 1
    }
  }

  it should "build a generic tx" in new Fixture {
    test {

      val tx =
        transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)
      val tx2 =
        transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)
      val tx3 =
        transfer(publicKey, address3, transferAmount, privateKey, clique.masterRestPort)
      val tx4 =
        transfer(publicKey, address4, transferAmount, privateKey, clique.masterRestPort)

      confirmTxs(tx, tx2, tx3, tx4)

      // Force Two utxos
      val amount2 = transferAmount.mulUnsafe(U256.Two) - transferAmount.divUnsafe(U256.Two)
      val amount3 = balance(address3).divUnsafe(U256.Two)
      val amount4 = balance(address4).divUnsafe(U256.Two)

      val inputs: AVector[BuildMultiAddressesTransaction.Source] = AVector(
        BuildMultiAddressesTransaction.Source(pubKey(publicKey).bytes, destinations(amount)),
        BuildMultiAddressesTransaction.Source(pubKey(publicKey2).bytes, destinations(amount2)),
        BuildMultiAddressesTransaction
          .Source(
            pubKey(publicKey3).bytes,
            destinations(amount3, lockTime = Some(TimeStamp.now()))
          ),
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

      // not only 1 utoxs as there are input with locktime and message
      currentUTXOs(destAddress).utxos.length is 3
    }
  }

  it should "let only one address pay gas" in new Fixture {
    test {

      val tx =
        transfer(publicKey, address2, transferAmount, privateKey, clique.masterRestPort)
      val tx2 =
        transfer(publicKey, address3, transferAmount, privateKey, clique.masterRestPort)
      val tx3 =
        transfer(publicKey, address4, transferAmount, privateKey, clique.masterRestPort)

      clique.startMining()
      confirmTxs(tx, tx2, tx3)

      val initialAmount2 = balance(address2)
      val initialAmount3 = balance(address3)
      val initialAmount4 = balance(address4)
      val destAmount     = transferAmount.divUnsafe(U256.Two)

      val inputs: AVector[BuildMultiAddressesTransaction.Source] = AVector(
        BuildMultiAddressesTransaction
          .Source(pubKey(publicKey).bytes, destinations(destAmount), gasAmount = None),
        zeroGasSource(publicKey2, destAmount),
        zeroGasSource(publicKey3, destAmount),
        zeroGasSource(publicKey4, destAmount)
      )

      val genericTx = transferGeneric(
        inputs,
        AVector(privateKey, privateKey2, privateKey3, privateKey4),
        clique.masterRestPort
      )

      confirmTx(genericTx, clique.masterRestPort)

      currentUTXOs(destAddress).utxos.length is 1

      def checkAddressPaidNoGas(address: String, initialAmount: U256) = {
        balance(address) is initialAmount.subUnsafe(destAmount)
      }

      checkAddressPaidNoGas(address2, initialAmount2)
      checkAddressPaidNoGas(address3, initialAmount3)
      checkAddressPaidNoGas(address4, initialAmount4)
    }
  }

  trait Fixture extends CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)

    val addressGroupIndex = Address.fromBase58(address).get.groupIndex

    lazy val amount = balance(address).divUnsafe(U256.Two)

    val (address2, publicKey2, privateKey2) = generateAccount(addressGroupIndex)
    val (address3, publicKey3, privateKey3) = generateAccount(addressGroupIndex)
    val (address4, publicKey4, privateKey4) = generateAccount(addressGroupIndex)
    val (destAddress, _, _)                 = generateAccount(addressGroupIndex)

    def pubKey(str: String): PublicKey = PublicKey.unsafe(Hex.unsafe(str))

    def currentUTXOs(addr: String) = {
      request[UTXOs](getUTXOs(addr), clique.masterRestPort)
    }

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

    def zeroGasSource(publicKey: String, amount: U256) =
      BuildMultiAddressesTransaction
        .Source(pubKey(publicKey).bytes, destinations(amount), gasAmount = Some(GasBox.zero))

    def balance(address: String): U256 = {

      request[Balance](getBalance(address), clique.masterRestPort).balance.value
    }

    def confirmTxs(txs: SubmitTxResult*) = {
      txs.foreach { tx =>
        confirmTx(tx, clique.masterRestPort)
      }
    }

    def test(f: => Assertion) = {
      start()
      f
      stop()
    }

    def start() = {
      clique.start()
      clique.startMining()
    }

    def stop() = {
      clique.stopMining()
      clique.stop()
    }
  }
}
