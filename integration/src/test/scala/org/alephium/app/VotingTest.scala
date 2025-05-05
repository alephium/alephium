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
import org.alephium.protocol.model.{
  dustUtxoAmount,
  Address,
  BlockHash,
  ContractId,
  TokenId,
  TransactionId
}
import org.alephium.protocol.vm
import org.alephium.util._
import org.alephium.wallet.api.model._

class VotingTest extends AlephiumActorSpec {
  it should "test the voting pipeline" in new VotingFixture {

    val admin  = wallets.head
    val voters = wallets.tail
    val ContractRef(contractId, contractAddress @ Address.Contract(_), contractCode) =
      buildDeployContractTx(admin, voters, U256.unsafe(voters.size))
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

    checkVoteCastedEventsByTxId(voteForTxInfos)
    checkVoteCastedEventsByTxId(voteAgainstTxInfos)
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
      event.eventIndex is 0
    }

    def checkVoteCastedEventsByTxId(infos: Seq[(String, Boolean, TransactionId)]) = {
      infos.foreach { info =>
        val (address, choice, txId) = info
        val response = request[ContractEventsByTxId](
          getEventsByTxId(txId.toHexString),
          restPort
        )

        val events = response.events.filter(event => isBlockInMainChain(event.blockHash))
        events.length is 1
        val event = events.head
        event.contractAddress is contractAddress
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

      returnedResult.toSeq is expectedResult
    }

    def checkVotingClosedEvent(event: ContractEvent) = {
      event.eventIndex is 2
    }

    def checkState(nbYes: Int, nbNo: Int, isClosed: Boolean, isInitialized: Boolean) = {
      val contractState =
        request[ContractState](
          getContractState(contractAddress.toBase58),
          restPort
        )
      contractState.mutFields.get(0).get is ValU256(U256.unsafe(nbYes))
      contractState.mutFields.get(1).get is ValU256(U256.unsafe(nbNo))
      contractState.mutFields.get(2).get is ValBool(isClosed)
      contractState.mutFields.get(3).get is ValBool(isInitialized)
      contractState.immFields.get(0).get is ValAddress(Address.fromBase58(admin.activeAddress).get)
      contractState.immFields.drop(1) is AVector.from[Val](
        voters.map(v => ValAddress(Address.fromBase58(v.activeAddress).get))
      )
    }

    def checkEvents(contractAddress: Address, startCount: Int)(
        validate: (AVector[ContractEvent]) => Any
    ) = {
      val response =
        request[ContractEvents](
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
  def buildDeployContractTx(admin: Wallet, voters: Seq[Wallet], tokenAmount: U256): ContractRef = {
    val allocationTransfers = voters.zipWithIndex
      .map { case (_, i) =>
        s"""
           |transferToken!(admin, voters[$i], ALPH, $dustAmount)
           |transferTokenFromSelf!(voters[$i], selfTokenId!(), 1)""".stripMargin
      }
      .mkString("\n")
    // scalastyle:off no.equal
    val votingContract =
      s"""
         |Contract Voting(
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
         |  @using(preapprovedAssets = true, assetsInContract = true, updateFields = true)
         |  pub fn allocateTokens() -> () {
         |     assert!(initialized == false, 0)
         |     assert!(callerAddress!() == admin, 0)
         |     ${allocationTransfers}
         |     yes = 0
         |     no = 0
         |     initialized = true
         |
         |     emit VotingStarted()
         |  }
         |
         |  @using(preapprovedAssets = true, assetsInContract = true, updateFields = true)
         |  pub fn vote(choice: Bool, voter: Address) -> () {
         |    assert!(initialized == true && isClosed == false, 0)
         |    transferToken!(voter, admin, ALPH, $dustAmount)
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
         |   @using(updateFields = true)
         |   pub fn close() -> () {
         |     assert!(initialized == true && isClosed == false, 0)
         |     assert!(callerAddress!() == admin, 0)
         |     isClosed = true
         |
         |     emit VotingClosed()
         |   }
         | }
      """.stripMargin
    // scalastyle:on no.equal
    val votersList: AVector[vm.Val] =
      AVector.from(
        voters.map(wallet =>
          vm.Val.Address(Address.fromBase58(wallet.activeAddress).get.lockupScript)
        )
      )
    voters.map(wallet => s"@${wallet.activeAddress}").mkString(",")
    val initialImmFields = AVector[vm.Val](
      vm.Val.Address(Address.fromBase58(admin.activeAddress).get.lockupScript)
    ) ++ votersList
    val initialMutFields = AVector[vm.Val](
      vm.Val.U256(U256.Zero),
      vm.Val.U256(U256.Zero),
      vm.Val.False,
      vm.Val.False
    )
    contract(
      admin,
      votingContract,
      Some(initialImmFields),
      Some(initialMutFields),
      Some(tokenAmount)
    )
  }
  // scalastyle:on method.length

  def allocateTokens(
      adminWallet: Wallet,
      votersWallets: Seq[Wallet],
      contractId: String,
      contractCode: String
  ): SubmitTxResult = {
    val allocationScript =
      s"""
         |TxScript TokenAllocation {
         |  let voting = Voting(#${contractId})
         |  let caller = callerAddress!()
         |  voting.allocateTokens{caller -> ALPH: $dustAmount * ${votersWallets.size}}()
         |}
        $contractCode
      """.stripMargin
    script(
      adminWallet.publicKey.toHexString,
      allocationScript,
      adminWallet.creation.walletName,
      attoAlphAmount = Some(Amount(dustUtxoAmount * votersWallets.size))
    )
  }

  def vote(
      voterWallet: Wallet,
      contractId: String,
      choice: Boolean,
      contractCode: String
  ): SubmitTxResult = {
    val votingScript =
      s"""
         |TxScript VotingScript {
         |  let caller = callerAddress!()
         |  let voting = Voting(#$contractId)
         |  voting.vote{caller -> ALPH: $dustAmount, #$contractId: 1}($choice, caller)
         |}
      $contractCode
      """.stripMargin
    script(
      voterWallet.publicKey.toHexString,
      votingScript,
      voterWallet.creation.walletName,
      attoAlphAmount = Some(Amount(dustUtxoAmount)),
      tokens = Some(TokenId.from(Hex.unsafe(contractId)).value -> 1)
    )
  }

  def close(adminWallet: Wallet, contractId: String, contractCode: String): SubmitTxResult = {
    val closingScript = s"""
                           |TxScript ClosingScript {
                           |  let voting = Voting(#${contractId})
                           |  voting.close()
                           |}
      $contractCode
      """.stripMargin
    script(adminWallet.publicKey.toHexString, closingScript, adminWallet.creation.walletName)
  }
}

trait WalletFixture extends CliqueFixture {
  override val configValues: Map[String, Any] = Map(("alephium.broker.broker-num", 1))
  val clique                                  = bootClique(1)
  val activeAddressesGroup                    = 0
  val genesisWalletName                       = "genesis-wallet"
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
      issueTokenAmount: Option[U256]
  ): ContractRef = {
    val compileResult = request[CompileContractResult](compileContract(code), restPort)
    val buildResult = request[BuildDeployContractTxResult](
      buildDeployContractTx(
        fromPublicKey = wallet.publicKey.toHexString,
        code = compileResult.bytecode,
        initialImmFields = initialImmFields,
        initialMutFields = initialMutFields,
        issueTokenAmount = issueTokenAmount
      ),
      restPort
    ).asInstanceOf[BuildSimpleDeployContractTxResult]
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
    ).asInstanceOf[BuildSimpleExecuteScriptTxResult]
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

  startWsClient(defaultRestMasterPort).futureValue.isClosed is false
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
