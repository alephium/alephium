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
import org.alephium.protocol.vm
import org.alephium.util._
import org.alephium.wallet.api.model.{Addresses, AddressInfo, WalletCreationResult}

class MultisigSmartContractTest extends AlephiumActorSpec {
  trait MultisigSmartContractFixture extends CliqueFixture {
    val clique = bootClique(nbOfNodes = 1)
    clique.start()
    val group    = clique.getGroup(address)
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

    val multisigAddress =
      request[BuildMultisigAddressResult](
        multisig(AVector(publicKey), 1),
        restPort
      ).address.toBase58

    val tx = transfer(publicKey, multisigAddress, transferAmount * 10, privateKey, restPort)
    confirmTx(tx, restPort)
  }

  it should "build multisig DeployContractTx" in new MultisigSmartContractFixture {
    val contract =
      s"""
         |Contract Foo(a: Bool, b: I256, c: U256, d: ByteVec) {
         |  pub fn foo() -> (Bool, I256, U256, ByteVec) {
         |    return a, b, c, d
         |  }
         |}
         |""".stripMargin

    val compileResult = request[CompileContractResult](compileContract(contract), restPort)
    val validFields = Some(
      AVector[vm.Val](
        vm.Val.True,
        vm.Val.I256(I256.unsafe(1000)),
        vm.Val.U256(U256.unsafe(1000)),
        vm.Val.ByteVec(ByteString(0, 0))
      )
    )
    unitRequest(
      buildMultisigDeployContractTx(
        multisigAddress,
        AVector(publicKey),
        compileResult.bytecode,
        initialFields = validFields
      ),
      restPort
    )
    clique.stopMining()
    clique.stop()
  }

  it should "build multisig TxScript" in new MultisigSmartContractFixture {
    val script =
      s"""
         |TxScript Main {
         |  assert!(1 == 2, 0)
         |}
         |""".stripMargin
    val compileResult = request[CompileScriptResult](compileScript(script), restPort)

    unitRequest(
      buildMultisigExecuteScriptTx(
        multisigAddress,
        AVector(publicKey),
        compileResult.bytecodeTemplate
      ),
      restPort
    )
    clique.stopMining()
    clique.stop()
  }
}
