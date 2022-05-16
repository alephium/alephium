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
import org.alephium.protocol.{ALPH, BlockHash, Hash, PublicKey}
import org.alephium.protocol.model.{Address, ContractId}
import org.alephium.util._
import org.alephium.wallet.api.model._

class VotingTest extends AlephiumActorSpec {
  it should "test the voting pipeline" in new VotingFixture {

    val admin  = wallets.head
    val voters = wallets.tail
    val ContractRef(contractId, contractAddress @ Address.Contract(_), contractCode) =
      deployContract(admin, voters, U256.unsafe(voters.size))
    checkState(0, 0, false, false)

    allocateTokens(admin, voters, contractId.toHexString, contractCode)
    checkState(0, 0, false, true)

    checkEvents(contractAddress, 0) { events =>
      events.length is 1
      checkVotingStartedEvent(events.head)
    }
    val countAfterVotingStarted = getEventsCurrentCount(contractAddress)

    val nbYes = voters.size - 1
    val nbNo  = voters.size - nbYes
    val voteForTxInfos = voters.take(nbYes).map { wallet =>
      val txId = vote(wallet, contractId.toHexString, true, contractCode).txId
      (wallet.activeAddress, true, txId)
    }
    val voteAgainstTxInfos = voters.drop(nbYes).map { wallet =>
      val txId = vote(wallet, contractId.toHexString, false, contractCode).txId
      (wallet.activeAddress, false, txId)
    }
    checkState(nbYes, nbNo, false, true)

    checkVoteCastedScriptEvents(voteForTxInfos)
    checkVoteCastedScriptEvents(voteAgainstTxInfos)
    checkEvents(contractAddress, countAfterVotingStarted)(checkVoteCastedEvents)

    val countAfterVotingCasted = getEventsCurrentCount(contractAddress)

    close(admin, contractId.toHexString, contractCode)
    checkState(nbYes, nbNo, true, true)

    checkEvents(contractAddress, countAfterVotingCasted) { events =>
      events.length is 1
      checkVotingClosedEvent(events.head)
    }

    // Check all events for the contract from the beginning
    checkEvents(contractAddress, 0) { events =>
      val totalEventsNum = voters.length + 2
      events.length is totalEventsNum

      checkVotingStartedEvent(events.head)
      checkVoteCastedEvents(events.tail.take(voters.length))
      checkVotingClosedEvent(events.last)
    }

    clique.selfClique().nodes.foreach { peer =>
      request[Boolean](stopMining, peer.restPort) is true
    }
    clique.stop()

    def checkVotingStartedEvent(event: ContractEvent) = {
      val votingStartedEvent = event.asInstanceOf[ContractEvent]
      votingStartedEvent.eventIndex is 0
      votingStartedEvent.contractId is contractAddress.lockupScript.contractId
    }

    def checkVoteCastedScriptEvents(infos: Seq[(String, Boolean, Hash)]) = {
      infos.foreach { info =>
        val (address, choice, txId) = info
        val response = request[Events](
          getEventsByTxId(txId.toHexString),
          restPort
        )

        val events = response.events.filter(event => isBlockInMainChain(event.blockHash))
        events.length is 1
        val event = events.head
        event.txId is txId
        event.eventIndex is 1
        event.fields.length is 2
        event.fields(0) is ValAddress(Address.fromBase58(address).get)
        event.fields(1) is ValBool(choice)
      }
    }

    def checkVoteCastedEvents(events: AVector[ContractEvent]) = {
      val expectedResult = voters.take(nbYes).map { wallet =>
        (1, wallet.activeAddress, true)
      } ++ voters.drop(nbYes).map { wallet =>
        (1, wallet.activeAddress, false)
      }

      val returnedResult = events.map { event =>
        val voterAddress = event.fields(0).asInstanceOf[ValAddress]
        val decision     = event.fields(1).asInstanceOf[ValBool]
        (event.eventIndex, voterAddress.value.toBase58, decision.value)
      }

      returnedResult.toSeq is expectedResult.toSeq
    }

    def checkVotingClosedEvent(event: ContractEvent) = {
      val votingClosedEvent = event.asInstanceOf[ContractEvent]
      votingClosedEvent.eventIndex is 2
      votingClosedEvent.contractId is contractAddress.lockupScript.contractId
    }

    def checkState(nbYes: Int, nbNo: Int, isClosed: Boolean, isInitialized: Boolean) = {
      val contractState =
        request[ContractState](
          getContractState(contractAddress.toBase58, activeAddressesGroup),
          restPort
        )
      contractState.fields.get(0).get is ValU256(U256.unsafe(nbYes))
      contractState.fields.get(1).get is ValU256(U256.unsafe(nbNo))
      contractState.fields.get(2).get is ValBool(isClosed)
      contractState.fields.get(3).get is ValBool(isInitialized)
      contractState.fields.get(4).get is ValAddress(Address.fromBase58(admin.activeAddress).get)
      contractState.fields.drop(5) is AVector.from[Val](
        voters.map(v => ValAddress(Address.fromBase58(v.activeAddress).get))
      )
    }

    def checkEvents(contractAddress: Address, startCount: Int)(
        validate: (AVector[ContractEvent]) => Any
    ) = {
      val response =
        request[Events](
          getContractEvents(startCount, contractAddress),
          restPort
        )

      // Filter out events from the occasional orphan blocks
      val events = response.events.filter(event => isBlockInMainChain(event.blockHash))
      validate(events)
    }

    def isBlockInMainChain(blockHash: BlockHash): Boolean = {
      request[Boolean](
        isBlockInMainChain(blockHash.toHexString),
        restPort
      )
    }

    def getEventsCurrentCount(contractAddress: Address): Int = {
      request[Int](getContractEventsCurrentCount(contractAddress), restPort)
    }
  }
}

trait VotingFixture extends WalletFixture {
  // scalastyle:off method.length
  def deployContract(admin: Wallet, voters: Seq[Wallet], tokenAmount: U256): ContractRef = {
    val allocationTransfers = voters.zipWithIndex
      .map { case (_, i) =>
        s"""
          |transferAlph!(admin, voters[$i], $utxoFee)
          |transferTokenFromSelf!(voters[$i], selfTokenId!(), 1)""".stripMargin
      }
      .mkString("\n")
    // scalastyle:off no.equal
    val votingContract = s"""
        |TxContract Voting(
        |  mut yes: U256,
        |  mut no: U256,
        |  mut isClosed: Bool,
        |  mut initialized: Bool,
        |  admin: Address,
        |  voters: [Address; ${voters.size}]
        |) {
        |
        |  event VotingStarted()
        |  event VoteCasted(voter: Address, result: Bool)
        |  event VotingClosed()
        |
        |  pub payable fn allocateTokens() -> () {
        |     assert!(initialized == false)
        |     assert!(txCaller!(txCallerSize!() - 1) == admin)
        |     ${allocationTransfers}
        |     yes = 0
        |     no = 0
        |     initialized = true
        |
        |     emit VotingStarted()
        |  }
        |
        |  pub payable fn vote(choice: Bool, voter: Address) -> () {
        |    assert!(initialized == true && isClosed == false)
        |    transferAlph!(voter, admin, $utxoFee)
        |    transferTokenToSelf!(voter, selfTokenId!(), 1)
        |
        |    emit VoteCasted(voter, choice)
        |
        |    if (choice == true) {
        |       yes = yes + 1
        |    } else {
        |       no = no + 1
        |    }
        |  }
        |
        |   pub fn close() -> () {
        |     assert!(initialized == true && isClosed == false)
        |     assert!(txCaller!(txCallerSize!() - 1) == admin)
        |     isClosed = true
        |
        |     emit VotingClosed()
        |   }
        | }
      """.stripMargin
    // scalastyle:on no.equal
    val votersList: AVector[Val] =
      AVector.from(voters.map(wallet => ValAddress(Address.fromBase58(wallet.activeAddress).get)))
    voters.map(wallet => s"@${wallet.activeAddress}").mkString(",")
    val initialFields = AVector[Val](
      ValU256(U256.Zero),
      ValU256(U256.Zero),
      Val.False,
      Val.False,
      ValAddress(Address.fromBase58(admin.activeAddress).get)
    ) ++ votersList
    contract(admin, votingContract, Some(initialFields), Some(tokenAmount))
  }
  // scalastyle:on method.length

  def allocateTokens(
      adminWallet: Wallet,
      votersWallets: Seq[Wallet],
      contractId: String,
      contractCode: String
  ): TxResult = {
    val allocationScript = s"""
        |TxScript TokenAllocation {
        |    pub payable fn main() -> () {
        |      let voting = Voting(#${contractId})
        |      let caller = txCaller!(0)
        |      approveAlph!(caller, $utxoFee * ${votersWallets.size})
        |      voting.allocateTokens()
        |    }
        |}
        $contractCode
      """.stripMargin
    script(adminWallet.publicKey.toHexString, allocationScript, adminWallet.creation.walletName)
  }

  def vote(
      voterWallet: Wallet,
      contractId: String,
      choice: Boolean,
      contractCode: String
  ): TxResult = {
    val votingScript = s"""
      |TxScript VotingScript {
      |  pub payable fn main() -> () {
      |    let caller = txCaller!(txCallerSize!() - 1)
      |    approveToken!(caller, #${contractId}, 1)
      |    let voting = Voting(#${contractId})
      |    approveAlph!(caller, $utxoFee)
      |    voting.vote($choice, caller)
      |  }
      |}
      $contractCode
      """.stripMargin
    script(voterWallet.publicKey.toHexString, votingScript, voterWallet.creation.walletName)
  }

  def close(adminWallet: Wallet, contractId: String, contractCode: String): TxResult = {
    val closingScript = s"""
      |TxScript ClosingScript {
      |  pub payable fn main() -> () {
      |    let voting = Voting(#${contractId})
      |    voting.close()
      |  }
      |}
      $contractCode
      """.stripMargin
    script(adminWallet.publicKey.toHexString, closingScript, adminWallet.creation.walletName)
  }
}

trait WalletFixture extends CliqueFixture {
  override val configValues = Map(("alephium.broker.broker-num", 1))
  val clique                = bootClique(1)
  val activeAddressesGroup  = 0
  val genesisWalletName     = "genesis-wallet"
  def submitTx(unsignedTx: String, txId: Hash, walletName: String): TxResult = {
    val signature =
      request[SignResult](sign(walletName, s"${txId.toHexString}"), restPort).signature
    val tx = request[TxResult](
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
      initialFields: Option[AVector[Val]],
      issueTokenAmount: Option[U256]
  ): ContractRef = {
    val compileResult = request[CompileContractResult](compileContract(code), restPort)
    val buildResult = request[BuildContractDeployScriptTxResult](
      buildContract(
        fromPublicKey = wallet.publicKey.toHexString,
        code = compileResult.bytecodeUnsafe,
        initialFields = initialFields,
        issueTokenAmount = issueTokenAmount
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

  def script(publicKey: String, code: String, walletName: String) = {
    val compileResult = request[CompileScriptResult](compileScript(code), restPort)
    val buildResult = request[BuildScriptTxResult](
      buildScript(
        fromPublicKey = publicKey,
        code = compileResult.bytecodeUnsafe
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
  val utxoFee = "50000000000000"
}

final case class ContractRef(contractId: ContractId, contractAddress: Address, code: String)
final case class Wallet(
    creation: WalletCreation,
    result: WalletCreationResult,
    activeAddress: String,
    publicKey: PublicKey
)
