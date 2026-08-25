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

package org.alephium.wallet.service

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

import sttp.model.StatusCode

import org.alephium.api.{model => api}
import org.alephium.api.ApiError
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{PublicKey, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, BlockHash, GroupIndex, TransactionId}
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util.{discard, AlephiumFutureSpec, AVector, Duration, TimeStamp, U256}
import org.alephium.wallet.config.WalletConfigFixture
import org.alephium.wallet.service.WalletService.SweepBatchSettings
import org.alephium.wallet.service.WalletSweepBatchingSpec.PreparedTransaction
import org.alephium.wallet.web.{BlockFlowClient, WalletServer}

class WalletSweepBatchingSpec extends AlephiumFutureSpec {

  it should "use a single batch lane for a normal wallet" in new Fixture {
    val blockFlowClient = new BlockFlowClientMock(transactionsPerAddress = 1, Some(3))
    val walletService   = createWalletService(blockFlowClient)
    createWallet(walletService, isMiner = false)
    walletService.deriveNextAddress(walletName, None).rightValue
    walletService.deriveNextAddress(walletName, None).rightValue

    val sweepResult = walletService.sweepAllAddresses(
      walletName,
      destination,
      None,
      None,
      None,
      None
    )

    eventually {
      blockFlowClient.submittedTransactions.length is 2
      blockFlowClient.allPreparedBeforeFirstSubmission is true
    }
    sweepResult.isCompleted is false

    blockFlowClient.confirm(blockFlowClient.submittedTransactions.map(_.txId))

    eventually {
      blockFlowClient.submittedTransactions.length is 3
    }
    val results = sweepResult.futureValue.rightValue
    results.map(_._1) is blockFlowClient.preparedTransactions.map(_.txId)
    blockFlowClient.statusRequests.contains(results.last._1) is false
  }

  it should "run one independent batch lane per miner group" in new Fixture {
    val blockFlowClient = new BlockFlowClientMock(transactionsPerAddress = 3, Some(groupNum))
    val walletService   = createWalletService(blockFlowClient)
    createWallet(walletService, isMiner = true)

    val sweepResult = walletService.sweepAllAddresses(
      walletName,
      destination,
      None,
      None,
      None,
      None
    )

    eventually {
      val submittedByGroup = blockFlowClient.submittedTransactions.groupBy(_.fromGroup)
      submittedByGroup.size is groupNum
      submittedByGroup.values.foreach(_.length is 2)
      blockFlowClient.allPreparedBeforeFirstSubmission is true
    }
    sweepResult.isCompleted is false

    val firstGroup = GroupIndex.unsafe(0)
    blockFlowClient.confirm(
      blockFlowClient.submittedTransactions.filter(_.fromGroup == firstGroup).map(_.txId)
    )

    eventually {
      val submittedByGroup = blockFlowClient.submittedTransactions.groupBy(_.fromGroup)
      submittedByGroup(firstGroup).length is 3
      submittedByGroup.filterNot(_._1 == firstGroup).values.foreach(_.length is 2)
    }
    sweepResult.isCompleted is false

    blockFlowClient.confirm(
      blockFlowClient.submittedTransactions.filterNot(_.fromGroup == firstGroup).map(_.txId)
    )

    val results          = sweepResult.futureValue.rightValue
    val submittedByGroup = blockFlowClient.submittedTransactions.groupBy(_.fromGroup)
    submittedByGroup.values.foreach(_.length is 3)
    results.map(_._1) is blockFlowClient.preparedTransactions.map(_.txId)
    submittedByGroup.values.foreach { lane =>
      blockFlowClient.statusRequests.contains(lane.last.txId) is false
    }
  }

  it should "prepare every normal-wallet address before submitting" in new Fixture {
    val blockFlowClient =
      new BlockFlowClientMock(transactionsPerAddress = 1, None, failPreparationAt = Some(1))
    val walletService = createWalletService(blockFlowClient)
    createWallet(walletService, isMiner = false)
    walletService.deriveNextAddress(walletName, None).rightValue
    walletService.deriveNextAddress(walletName, None).rightValue

    val error = walletService
      .sweepAllAddresses(walletName, destination, None, None, None, None)
      .futureValue
      .leftValue

    error is a[WalletService.BlockFlowClientError]
    blockFlowClient.prepareCount is 2
    blockFlowClient.submittedTransactions is AVector.empty[PreparedTransaction]
  }

  it should "stop a lane when a submitted sweep transaction is conflicted" in new Fixture {
    val blockFlowClient = new BlockFlowClientMock(transactionsPerAddress = 3, Some(1))
    val walletService   = createWalletService(blockFlowClient)
    createWallet(walletService, isMiner = false)

    val sweepResult = walletService.sweepActiveAddress(
      walletName,
      destination,
      None,
      None,
      None,
      None
    )

    eventually {
      blockFlowClient.submittedTransactions.length is 2
    }
    blockFlowClient.conflict(blockFlowClient.submittedTransactions.head.txId)

    sweepResult.futureValue.leftValue is a[WalletService.SweepTransactionConflicted]
    blockFlowClient.submittedTransactions.length is 2
  }

  it should "time out a lane whose batch remains unconfirmed" in new Fixture {
    val blockFlowClient = new BlockFlowClientMock(transactionsPerAddress = 3, Some(1))
    val walletService = createWalletService(
      blockFlowClient,
      confirmationTimeout = Duration.ofMillisUnsafe(40)
    )
    createWallet(walletService, isMiner = false)

    val error = walletService
      .sweepActiveAddress(walletName, destination, None, None, None, None)
      .futureValue
      .leftValue

    error is a[WalletService.SweepConfirmationTimeout]
    blockFlowClient.submittedTransactions.length is 2
  }

  it should "map sweep confirmation failures to wallet HTTP errors" in {
    val txId       = TransactionId.generate
    val timeout    = WalletService.SweepConfirmationTimeout(AVector(txId))
    val conflicted = WalletService.SweepTransactionConflicted(txId)

    val timeoutApiError = WalletServer.toApiError(timeout)
    timeoutApiError is a[ApiError.GatewayTimeout]
    timeoutApiError.detail is timeout.message

    val conflictedApiError = WalletServer.toApiError(conflicted)
    conflictedApiError is a[ApiError.BadRequest]
    conflictedApiError.detail is conflicted.message
  }

  trait Fixture extends WalletConfigFixture {
    implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

    val walletName                 = "wallet-name"
    val password                   = "password"
    val destination: Address.Asset = Address.p2pkh(SignatureSchema.generatePriPub()._2)

    def createWalletService(
        blockFlowClient: BlockFlowClient,
        confirmationTimeout: Duration = Duration.ofSecondsUnsafe(10)
    ): WalletService = {
      WalletService(
        blockFlowClient,
        tempSecretDir,
        lockingTimeout,
        SweepBatchSettings(
          batchSize = 2,
          confirmationPollInterval = Duration.ofMillisUnsafe(2),
          confirmationTimeout = confirmationTimeout
        )
      )
    }

    def createWallet(walletService: WalletService, isMiner: Boolean): Unit = {
      discard(
        walletService
          .createWallet(
            password,
            Mnemonic.Size(12).get,
            isMiner,
            walletName,
            None
          )
          .rightValue
      )
    }
  }

  final private class BlockFlowClientMock(
      transactionsPerAddress: Int,
      expectedPreparationCount: Option[Int],
      failPreparationAt: Option[Int] = None
  )(implicit groupConfig: GroupConfig)
      extends BlockFlowClient {
    private val lock                 = new Object
    private val prepared             = mutable.ArrayBuffer.empty[PreparedTransaction]
    private val submitted            = mutable.ArrayBuffer.empty[PreparedTransaction]
    private val requestedStatuses    = mutable.ArrayBuffer.empty[TransactionId]
    private val statuses             = mutable.Map.empty[TransactionId, api.TxStatus]
    private var preparationCount     = 0
    private var preparedBeforeSubmit = true

    def preparedTransactions: AVector[PreparedTransaction] =
      lock.synchronized(AVector.from(prepared))

    def submittedTransactions: AVector[PreparedTransaction] =
      lock.synchronized(AVector.from(submitted))

    def statusRequests: AVector[TransactionId] =
      lock.synchronized(AVector.from(requestedStatuses))

    def prepareCount: Int = lock.synchronized(preparationCount)

    def allPreparedBeforeFirstSubmission: Boolean = lock.synchronized(preparedBeforeSubmit)

    def confirm(txIds: AVector[TransactionId]): Unit = lock.synchronized {
      txIds.foreach { txId =>
        statuses.update(txId, api.Confirmed(BlockHash.generate, 0, 1, 1, 1))
      }
    }

    def conflict(txId: TransactionId): Unit = lock.synchronized {
      statuses.update(txId, api.Conflicted(BlockHash.generate, 0, 1, 1, 1))
    }

    override def fetchBalance(
        address: api.Address
    ): Future[Either[ApiError[_ <: StatusCode], (api.Amount, api.Amount)]] =
      unsupported

    override def prepareTransaction(
        fromPublicKey: PublicKey,
        destinations: AVector[api.Destination],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[ApiError[_ <: StatusCode], api.BuildTransferTxResult]] =
      unsupported

    override def prepareSweepActiveAddressTransaction(
        fromPublicKey: PublicKey,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[ApiError[_ <: StatusCode], api.BuildSweepAddressTransactionsResult]] = {
      val preparationIndex = lock.synchronized {
        val index = preparationCount
        preparationCount += 1
        index
      }
      if (failPreparationAt.contains(preparationIndex)) {
        Future.successful(Left(ApiError.BadRequest("Cannot prepare sweep transaction")))
      } else {
        val fromGroup = LockupScript.p2pkh(fromPublicKey).groupIndex
        val toGroup   = address.groupIndex
        val transactions: AVector[api.SweepAddressTransaction] =
          AVector.tabulate(transactionsPerAddress) { _ =>
            val txId        = TransactionId.generate
            val unsignedTx  = txId.toHexString
            val transaction = PreparedTransaction(txId, unsignedTx, fromGroup, toGroup)
            lock.synchronized(prepared.addOne(transaction))
            api.SweepAddressTransaction(txId, unsignedTx, GasBox.unsafe(1), GasPrice(U256.One))
          }
        Future.successful(
          Right(
            api.BuildSweepAddressTransactionsResult(
              transactions,
              fromGroup.value,
              toGroup.value
            )
          )
        )
      }
    }

    override def postTransaction(
        tx: String,
        signature: Signature,
        fromGroup: Int
    ): Future[Either[ApiError[_ <: StatusCode], api.SubmitTxResult]] = {
      val transaction = lock.synchronized {
        if (submitted.isEmpty) {
          expectedPreparationCount.foreach { expected =>
            preparedBeforeSubmit = preparationCount == expected
          }
        }
        val preparedTransaction = prepared
          .find(_.unsignedTx == tx)
          .getOrElse(throw new IllegalArgumentException(s"Unknown transaction $tx"))
        submitted.addOne(preparedTransaction)
        preparedTransaction
      }
      Future.successful(
        Right(api.SubmitTxResult(transaction.txId, fromGroup, transaction.toGroup.value))
      )
    }

    override def fetchTransactionStatus(
        txId: TransactionId,
        fromGroup: GroupIndex,
        toGroup: GroupIndex
    ): Future[Either[ApiError[_ <: StatusCode], api.TxStatus]] = {
      val status = lock.synchronized {
        requestedStatuses.addOne(txId)
        statuses.getOrElse(txId, api.MemPooled())
      }
      Future.successful(Right(status))
    }

    private def unsupported[A]: Future[A] =
      Future.failed(new UnsupportedOperationException("Not used by this test"))
  }
}

object WalletSweepBatchingSpec {
  final case class PreparedTransaction(
      txId: TransactionId,
      unsignedTx: String,
      fromGroup: GroupIndex,
      toGroup: GroupIndex
  )
}
