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
import org.alephium.protocol.{ALPH, PublicKey}
import org.alephium.protocol.model.{dustUtxoAmount, Address, ContractId, TokenId, TransactionId}
import org.alephium.protocol.vm
import org.alephium.util._
import org.alephium.wallet.api.model._

trait WalletFixture extends CliqueFixture {
  override val configValues = Map(("alephium.broker.broker-num", 1))
  val clique                = bootClique(1)
  val activeAddressesGroup  = 0
  val genesisWalletName     = "genesis-wallet"
  def submitTx(unsignedTx: String, txId: TransactionId, walletName: String): SubmitTxResult = {
    val signature =
      request[SignResult](sign(walletName, s"${txId.toHexString}"), restPort).signature
    val tx = request[SubmitTxResult](
      submitTransaction(s"""
          {
            "unsignedTx": "$unsignedTx",
            "signature":"${signature.toHexString}"
          }"""),
      restPort
    )
    confirmTx(tx, restPort)
    tx
  }

  def contract(
      wallet: Wallet,
      code: String,
      initialImmFields: Option[AVector[vm.Val]],
      initialMutFields: Option[AVector[vm.Val]],
      issueTokenAmount: Option[U256],
      initialAttoAlphAmount: Option[U256] = None
  ): ContractRef = {
    val compileResult = request[CompileContractResult](compileContract(code), restPort)
    val buildResult = request[BuildDeployContractTxResult](
      buildDeployContractTx(
        fromPublicKey = wallet.publicKey.toHexString,
        code = compileResult.bytecode,
        initialImmFields = initialImmFields,
        initialMutFields = initialMutFields,
        issueTokenAmount = issueTokenAmount,
        initialAttoAlphAmount = initialAttoAlphAmount
      ),
      restPort
    )
    val txResult = submitTx(buildResult.unsignedTx, buildResult.txId, wallet.creation.walletName)
    val Confirmed(blockHash, _, _, _, _) =
      request[TxStatus](getTransactionStatus(txResult), restPort)
    val block = request[BlockEntry](getBlock(blockHash.toHexString), restPort)

    // scalastyle:off no.equal
    val tx: Transaction = block.transactions.find(_.unsigned.txId == txResult.txId).get
    // scalastyle:on no.equal

    val ContractOutput(_, _, _, contractAddress, _) =
      tx.generatedOutputs.find(_.isInstanceOf[ContractOutput]).get
    ContractRef(buildResult.contractAddress.contractId, contractAddress, code)
  }

  def script(
      publicKey: String,
      code: String,
      walletName: String,
      attoAlphAmount: Option[Amount] = None,
      tokens: Option[(TokenId, U256)] = None
  ) = {
    val compileResult = request[CompileScriptResult](compileScript(code), restPort)
    val buildResult = request[BuildExecuteScriptTxResult](
      buildExecuteScriptTx(
        fromPublicKey = publicKey,
        code = compileResult.bytecodeTemplate,
        attoAlphAmount = attoAlphAmount,
        tokens = tokens
      ),
      restPort
    )
    submitTx(buildResult.unsignedTx, buildResult.txId, walletName)
  }

  def createWallets(nWallets: Int, restPort: Int, walletsBalance: U256): Seq[Wallet] = {
    request[WalletRestoreResult](restoreWallet(password, mnemonic, genesisWalletName), restPort)
    unitRequest(unlockWallet(password, genesisWalletName), restPort)
    val walletsCreation: IndexedSeq[WalletCreation] =
      (1 to nWallets).map(i => WalletCreation("password", s"walletName-$i", isMiner = Some(true)))
    val walletsCreationResult: IndexedSeq[WalletCreationResult] =
      walletsCreation.map(walletCreation =>
        request[WalletCreationResult](
          createWallet(
            walletCreation.password,
            walletCreation.walletName,
            walletCreation.isMiner.get
          ),
          restPort
        )
      )
    walletsCreation.zip(walletsCreationResult).map {
      case (walletCreation, walletCreationResult) => {
        import org.alephium.api.UtilJson._
        unitRequest(unlockWallet(walletCreation.password, walletCreation.walletName), restPort)
        val addresses = request[AVector[MinerAddressesInfo]](
          getMinerAddresses(walletCreation.walletName),
          restPort
        )
        // scalastyle:off no.equal
        val newActiveAddress =
          addresses.head.addresses.toIterable
            .find(_.group.value == activeAddressesGroup)
            .get
            .address
            .toBase58
        // scalastyle:on no.equal
        unitRequest(
          postWalletChangeActiveAddress(walletCreation.walletName, newActiveAddress),
          restPort
        )
        val txResult = request[TransferResult](
          transferWallet(genesisWalletName, newActiveAddress, walletsBalance),
          restPort
        )
        confirmTx(txResult, restPort)
        val pubKey = request[AddressInfo](
          getAddressInfo(walletCreation.walletName, newActiveAddress),
          restPort
        ).publicKey
        Wallet(walletCreation, walletCreationResult, newActiveAddress, pubKey)
      }
    }
  }

  clique.start()

  val restPort = clique.masterRestPort
  request[Balance](getBalance(address), restPort) is initialBalance

  startWS(defaultWsMasterPort)
  clique.selfClique().nodes.foreach { peer => request[Boolean](startMining, peer.restPort) is true }

  val nWallets       = 5
  val walletsBalance = ALPH.alph(1000)
  val wallets        = createWallets(nWallets, restPort, walletsBalance)
  wallets.foreach(wallet =>
    request[Balance](getBalance(wallet.activeAddress), restPort).balance.value is walletsBalance
  )
  val dustAmount = dustUtxoAmount.toString()
}

final case class ContractRef(contractId: ContractId, contractAddress: Address, code: String)
final case class Wallet(
    creation: WalletCreation,
    result: WalletCreationResult,
    activeAddress: String,
    publicKey: PublicKey
)
