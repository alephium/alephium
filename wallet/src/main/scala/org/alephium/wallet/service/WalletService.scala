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

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.{Timer, TimerTask}
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory, TimeUnit}

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

import sttp.model.StatusCode

import org.alephium.api.{model => api}
import org.alephium.api.ApiError
import org.alephium.api.model.{
  Amount,
  BuildGrouplessTransferTxResult,
  BuildSimpleTransferTxResult,
  Destination,
  SweepAddressTransaction
}
import org.alephium.crypto.wallet.BIP32.ExtendedPrivateKey
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex, TransactionId}
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{discard, AVector, Duration, FutureCollection, Service, TimeStamp}
import org.alephium.wallet.Constants
import org.alephium.wallet.api.model.{Addresses, AddressInfo}
import org.alephium.wallet.storage.SecretStorage
import org.alephium.wallet.web.BlockFlowClient

// scalastyle:off file.size.limit
trait WalletService extends Service {
  import WalletService._

  def createWallet(
      password: String,
      mnemonicSize: Mnemonic.Size,
      isMiner: Boolean,
      walletName: String,
      mnemonicPassphrase: Option[String]
  ): Either[WalletError, (String, Mnemonic)]

  def restoreWallet(
      password: String,
      mnemonic: Mnemonic,
      isMiner: Boolean,
      walletName: String,
      mnemonicPassphrase: Option[String]
  ): Either[WalletError, String]

  def lockWallet(wallet: String): Either[WalletError, Unit]
  def unlockWallet(
      wallet: String,
      password: String,
      mnemonicPassphrase: Option[String]
  ): Either[WalletError, Unit]
  def deleteWallet(wallet: String, password: String): Either[WalletError, Unit]
  def getBalances(
      wallet: String
  ): Future[Either[WalletError, AVector[(Address.Asset, Amount, Amount)]]]
  def getAddresses(wallet: String): Either[WalletError, Addresses]
  def getAddressInfo(wallet: String, address: Address.Asset): Either[WalletError, AddressInfo]
  def getMinerAddresses(
      wallet: String
  ): Either[WalletError, AVector[AVector[AddressInfo]]]
  def transfer(
      wallet: String,
      destinations: AVector[Destination],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      utxosLimit: Option[Int]
  ): Future[Either[WalletError, (TransactionId, GroupIndex, GroupIndex)]]
  def sweepActiveAddress(
      wallet: String,
      address: Address.Asset,
      lockTime: Option[TimeStamp],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      utxosLimit: Option[Int]
  ): Future[Either[WalletError, AVector[(TransactionId, GroupIndex, GroupIndex)]]]
  def sweepAllAddresses(
      wallet: String,
      address: Address.Asset,
      lockTime: Option[TimeStamp],
      gas: Option[GasBox],
      gasPrice: Option[GasPrice],
      utxosLimit: Option[Int]
  ): Future[Either[WalletError, AVector[(TransactionId, GroupIndex, GroupIndex)]]]
  def sign(
      wallet: String,
      data: Hash
  ): Either[WalletError, Signature]
  def deriveNextAddress(
      wallet: String,
      groupOpt: Option[GroupIndex]
  ): Either[WalletError, AddressInfo]
  def deriveNextMinerAddresses(wallet: String): Either[WalletError, AVector[AddressInfo]]
  def changeActiveAddress(wallet: String, address: Address.Asset): Either[WalletError, Unit]
  def listWallets(): Either[WalletError, AVector[(String, Boolean)]]
  def getWallet(wallet: String): Either[WalletError, (String, Boolean)]
  def revealMnemonic(wallet: String, password: String): Either[WalletError, Mnemonic]
}

// scalastyle:off number.of.methods
object WalletService {

  sealed trait WalletError {
    def message: String
  }

  object WalletError {
    def from(error: SecretStorage.Error): WalletError =
      error match {
        case SecretStorage.Locked                    => WalletLocked
        case SecretStorage.CannotDeriveKey           => UnexpectedError
        case SecretStorage.CannotParseFile           => InvalidWalletFile
        case SecretStorage.SecretFileError           => InvalidWalletFile
        case SecretStorage.SecretFileAlreadyExists   => InvalidWalletFile
        case SecretStorage.CannotDecryptSecret       => InvalidPassword
        case SecretStorage.InvalidState              => UnexpectedError
        case SecretStorage.UnknownKey                => UnexpectedError
        case SecretStorage.SecretFileNotFound(file)  => WalletNotFound(file)
        case SecretStorage.InvalidMnemonicPassphrase => InvalidMnemonicPassphrase
      }
  }

  final case class InvalidWalletName(name: String) extends WalletError {
    val message: String = s"Invalid wallet name: $name"
  }

  final case class CannotCreateEncryptedFile(directory: Path) extends WalletError {
    val message: String = s"Cannot create encrypted file at $directory"
  }

  final case class UnknownAddress(address: Address) extends WalletError {
    val message: String = s"Unknown address: ${address.toBase58}"
  }

  case object WalletLocked extends WalletError {
    val message: String = s"Wallet is locked"
  }

  case object InvalidPassword extends WalletError {
    val message: String = s"Invalid password"
  }

  case object InvalidMnemonicPassphrase extends WalletError {
    val message: String = "Invalid mnemonic passphrase"
  }

  case object MinerWalletRequired extends WalletError {
    val message: String = s"Miner wallet is needed"
  }

  case object InvalidWalletFile extends WalletError {
    val message: String = s"Invalid wallet file"
  }

  case object UnexpectedError extends WalletError {
    val message: String = s"Unexpected error"
  }

  final case class WalletNotFound(file: File) extends WalletError {
    val message: String = s"Wallet ${file.getName()} not found"
  }

  final case class BlockFlowClientError(apiError: ApiError[_ <: StatusCode]) extends WalletError {
    val message: String = apiError.detail
  }

  final case class OtherError(message: String) extends WalletError

  private def sweepFailureMessage(
      reason: String,
      submittedTxIds: AVector[TransactionId],
      possiblySubmittedTxIds: AVector[TransactionId]
  ): String = {
    val submitted = Option.when(submittedTxIds.nonEmpty)(
      s"Submitted sweep transaction ids: ${submittedTxIds.map(_.toHexString).mkString(", ")}"
    )
    val possiblySubmitted = Option.when(possiblySubmittedTxIds.nonEmpty)(
      s"Sweep transaction ids with unknown submission status: " +
        possiblySubmittedTxIds.map(_.toHexString).mkString(", ")
    )
    (AVector(reason) ++ AVector.from(submitted) ++ AVector.from(possiblySubmitted)).mkString(". ")
  }

  final case class SweepSubmissionFailed(
      apiError: ApiError[_ <: StatusCode],
      submittedTxIds: AVector[TransactionId],
      possiblySubmittedTxIds: AVector[TransactionId]
  ) extends WalletError {
    val message: String =
      sweepFailureMessage(apiError.detail, submittedTxIds, possiblySubmittedTxIds)
  }

  final case class SweepTransactionConflicted(
      txId: TransactionId,
      submittedTxIds: AVector[TransactionId],
      possiblySubmittedTxIds: AVector[TransactionId]
  ) extends WalletError {
    val message: String = sweepFailureMessage(
      s"Sweep transaction ${txId.toHexString} is conflicted",
      submittedTxIds,
      possiblySubmittedTxIds
    )
  }

  object SweepTransactionConflicted {
    def apply(txId: TransactionId): SweepTransactionConflicted =
      new SweepTransactionConflicted(txId, AVector.empty, AVector.empty)
  }

  final case class SweepConfirmationTimeout(
      txIds: AVector[TransactionId],
      submittedTxIds: AVector[TransactionId],
      possiblySubmittedTxIds: AVector[TransactionId]
  ) extends WalletError {
    val message: String = sweepFailureMessage(
      s"Timed out waiting for sweep transaction confirmation: " +
        txIds.map(_.toHexString).mkString(", "),
      submittedTxIds,
      possiblySubmittedTxIds
    )
  }

  object SweepConfirmationTimeout {
    def apply(txIds: AVector[TransactionId]): SweepConfirmationTimeout =
      new SweepConfirmationTimeout(txIds, AVector.empty, AVector.empty)
  }

  final private[service] case class SweepBatchSettings(
      batchSize: Int,
      confirmationPollInterval: Duration,
      confirmationTimeout: Duration
  )

  final private case class PreparedSweepTransaction(
      txId: TransactionId,
      unsignedTx: String,
      signature: Signature,
      fromGroup: GroupIndex,
      toGroup: GroupIndex
  )

  final private case class QueuedSweepTransaction(
      index: Int,
      transaction: PreparedSweepTransaction
  )

  final private case class SubmittedSweepTransaction(
      index: Int,
      txId: TransactionId,
      fromGroup: GroupIndex,
      toGroup: GroupIndex
  ) {
    def result: (TransactionId, GroupIndex, GroupIndex) = (txId, fromGroup, toGroup)
  }

  final private case class SweepPostResult(
      queued: QueuedSweepTransaction,
      result: Either[WalletError, SubmittedSweepTransaction],
      submissionUnknown: Boolean
  )

  final private case class SweepFailure(
      cause: WalletError,
      submitted: AVector[SubmittedSweepTransaction],
      possiblySubmitted: AVector[QueuedSweepTransaction]
  ) {
    def addSubmitted(
        transactions: AVector[SubmittedSweepTransaction]
    ): SweepFailure = copy(submitted = transactions ++ submitted)

    def toWalletError: WalletError = {
      val submittedTxIds = submitted.sortBy(_.index).map(_.txId).distinct
      val possiblySubmittedTxIds =
        possiblySubmitted
          .sortBy(_.index)
          .map(_.transaction.txId)
          .distinct
          .filterNot(submittedTxIds.toSet)
      cause match {
        case BlockFlowClientError(apiError) =>
          SweepSubmissionFailed(apiError, submittedTxIds, possiblySubmittedTxIds)
        case SweepTransactionConflicted(txId, _, _) =>
          SweepTransactionConflicted(txId, submittedTxIds, possiblySubmittedTxIds)
        case SweepConfirmationTimeout(txIds, _, _) =>
          SweepConfirmationTimeout(txIds, submittedTxIds, possiblySubmittedTxIds)
        case error =>
          SweepSubmissionFailed(
            ApiError.InternalServerError(error.message),
            submittedTxIds,
            possiblySubmittedTxIds
          )
      }
    }
  }

  private[service] object SweepBatchSettings {
    val Default: SweepBatchSettings = SweepBatchSettings(
      batchSize = 2,
      confirmationPollInterval = Duration.ofSecondsUnsafe(1),
      confirmationTimeout = Duration.ofMinutesUnsafe(10)
    )
  }

  def apply(
      blockFlowClient: BlockFlowClient,
      secretDir: Path,
      lockingTimeout: Duration
  )(implicit groupConfig: GroupConfig, executionContext: ExecutionContext): WalletService = {

    apply(blockFlowClient, secretDir, lockingTimeout, SweepBatchSettings.Default)
  }

  private[service] def apply(
      blockFlowClient: BlockFlowClient,
      secretDir: Path,
      lockingTimeout: Duration,
      sweepBatchSettings: SweepBatchSettings
  )(implicit groupConfig: GroupConfig, executionContext: ExecutionContext): WalletService = {

    new Impl(blockFlowClient, secretDir, lockingTimeout, sweepBatchSettings)
  }

  final private case class StorageState(secretStorage: SecretStorage, timerTask: TimerTask)

  final private case class Storages(
      storages: mutable.Map[String, StorageState],
      lockingTimeout: Duration
  ) {
    private val isDaemon = true
    private val timer    = new Timer(isDaemon)

    private def lockTimerTask(storage: SecretStorage): TimerTask = new TimerTask {
      override def run(): Unit = storage.lock()
    }

    def addOne(filename: String, storage: SecretStorage): Unit = {
      discard(storages.synchronized {
        val timerTask = lockTimerTask(storage)
        timer.schedule(timerTask, lockingTimeout.millis)
        storages.addOne(filename -> StorageState(storage, timerTask))
      })
    }

    def remove(filename: String): Unit = {
      discard(storages.synchronized {
        storages.remove(filename)
      })
    }

    def get(wallet: String): Option[SecretStorage] = {
      storages.synchronized {
        storages.get(wallet).map { storageTs =>
          storageTs.timerTask.cancel()
          val timerTask = lockTimerTask(storageTs.secretStorage)
          timer.purge()
          timer.schedule(timerTask, lockingTimeout.millis)
          storages.update(wallet, storageTs.copy(timerTask = timerTask))
          storageTs.secretStorage
        }
      }
    }
  }

  private class Impl(
      blockFlowClient: BlockFlowClient,
      secretDir: Path,
      lockingTimeout: Duration,
      sweepBatchSettings: SweepBatchSettings
  )(implicit groupConfig: GroupConfig, val executionContext: ExecutionContext)
      extends WalletService {
    override def serviceName: String = "WalletService"

    private val secretStorages = Storages(mutable.Map.empty, lockingTimeout)

    private val sweepScheduler: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor(new ThreadFactory {
        override def newThread(runnable: Runnable): Thread = {
          val thread = new Thread(runnable, "wallet-sweep-confirmation")
          thread.setDaemon(true)
          thread
        }
      })

    private val path: AVector[Int] = Constants.path

    protected def startSelfOnce(): Future[Unit] = {
      if (Files.exists(secretDir)) {
        Future.unit
      } else {
        Future.fromTry(Try(discard(Files.createDirectories(secretDir))))
      }
    }

    protected def stopSelfOnce(): Future[Unit] = {
      discard(sweepScheduler.shutdownNow())
      Future.successful(())
    }

    override val subServices: ArraySeq[Service] = ArraySeq()

    private def createOrRestoreWallet(
        password: String,
        mnemonic: Mnemonic,
        mnemonicPassphrase: Option[String],
        isMiner: Boolean,
        walletName: String
    ): Either[WalletError, (String, Mnemonic)] = {
      for {
        file <- buildWalletFile(walletName)
        storage <- SecretStorage
          .create(mnemonic, mnemonicPassphrase, password, isMiner, file, path)
          .left
          .map(_ => CannotCreateEncryptedFile(secretDir))
        _ <- if (isMiner) computeNextMinerAddresses(storage) else Right(())
      } yield {
        val fileName = file.getName
        secretStorages.addOne(fileName, storage)
        (fileName, mnemonic)
      }
    }

    override def createWallet(
        password: String,
        mnemonicSize: Mnemonic.Size,
        isMiner: Boolean,
        walletName: String,
        mnemonicPassphrase: Option[String]
    ): Either[WalletError, (String, Mnemonic)] = {
      val mnemonic = Mnemonic.generate(mnemonicSize)

      createOrRestoreWallet(password, mnemonic, mnemonicPassphrase, isMiner, walletName)
    }

    override def restoreWallet(
        password: String,
        mnemonic: Mnemonic,
        isMiner: Boolean,
        walletName: String,
        mnemonicPassphrase: Option[String]
    ): Either[WalletError, String] = {
      createOrRestoreWallet(password, mnemonic, mnemonicPassphrase, isMiner, walletName).map {
        case (name, _) =>
          name
      }
    }

    override def lockWallet(wallet: String): Either[WalletError, Unit] = {
      Right(secretStorages.get(wallet).foreach(_.lock()))
    }

    override def unlockWallet(
        wallet: String,
        password: String,
        mnemonicPassphrase: Option[String]
    ): Either[WalletError, Unit] =
      withWalletM(wallet) { secretStorage =>
        secretStorage.unlock(password, mnemonicPassphrase).left.map(WalletError.from)
      }(Left.apply)

    override def deleteWallet(wallet: String, password: String): Either[WalletError, Unit] =
      withWalletM(wallet) { secretStorage =>
        secretStorage
          .delete(password)
          .map { _ =>
            secretStorages.remove(wallet)
          }
          .left
          .map(WalletError.from)
      }(Left.apply)

    override def getBalances(
        wallet: String
    ): Future[Either[WalletError, AVector[(Address.Asset, Amount, Amount)]]] =
      withAddressesFut(wallet) { case (_, addresses) =>
        Future
          .sequence(addresses.toSeq.map(getBalance))
          .map(AVector.from(_).mapE(identity))
      }

    override def getAddresses(
        wallet: String
    ): Either[WalletError, Addresses] = {
      withWallet(wallet) { secretStorage =>
        withPrivateKeys(secretStorage) { case (activeKey, privateKeys) =>
          Right(Addresses.from(activeKey, privateKeys))
        }
      }
    }

    override def getAddressInfo(
        wallet: String,
        address: Address.Asset
    ): Either[WalletError, AddressInfo] = {
      withWallet(wallet) { secretStorage =>
        withPrivateKeys(secretStorage) { case (_, privateKeys) =>
          (for {
            privateKey <- privateKeys.find(privateKey =>
              Address.p2pkh(privateKey.publicKey) == address
            )
          } yield AddressInfo.from(privateKey))
            .toRight(UnknownAddress(address): WalletError)
        }
      }
    }

    override def getMinerAddresses(
        wallet: String
    ): Either[WalletError, AVector[AVector[AddressInfo]]] = {
      withMinerWallet(wallet) { storage =>
        storage.getAllPrivateKeys() match {
          case Right((_, privateKeys)) =>
            Right(
              buildMinerAddresses(privateKeys).map(_.map { case (addressInfo, _) =>
                addressInfo
              })
            )
          case Left(error) => Left(WalletError.from(error))
        }
      }
    }

    override def transfer(
        wallet: String,
        destinations: AVector[Destination],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, (TransactionId, GroupIndex, GroupIndex)]] = {
      withPrivateKeyFut(wallet) { privateKey =>
        val pubKey = privateKey.publicKey
        blockFlowClient
          .prepareTransaction(pubKey, destinations, gas, gasPrice, utxosLimit)
          .flatMap {
            case Left(error) => Future.successful(Left(BlockFlowClientError(error)))
            case Right(buildTxResult: BuildSimpleTransferTxResult) =>
              val signature = SignatureSchema.sign(buildTxResult.txId.bytes, privateKey.privateKey)
              blockFlowClient
                .postTransaction(buildTxResult.unsignedTx, signature, buildTxResult.fromGroup)
                .map(
                  _.map(res =>
                    (res.txId, GroupIndex.unsafe(res.fromGroup), GroupIndex.unsafe(res.toGroup))
                  )
                )
                .map(_.left.map(BlockFlowClientError.apply))
            case Right(_: BuildGrouplessTransferTxResult) =>
              Future.successful(Left(OtherError("Multiple transactions result not supported yet")))
          }
      }
    }

    override def sweepActiveAddress(
        wallet: String,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, AVector[(TransactionId, GroupIndex, GroupIndex)]]] = {
      withPrivateKeyFut(wallet) { privateKey =>
        prepareSweepAddress(privateKey, address, lockTime, gas, gasPrice, utxosLimit).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(preparedTransactions) =>
            submitPreparedSweepTransactions(preparedTransactions, isMiner = false)
        }
      }
    }

    override def sweepAllAddresses(
        wallet: String,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, AVector[(TransactionId, GroupIndex, GroupIndex)]]] = {
      withSweepPrivateKeysFut(wallet) { case (isMiner, privateKeys) =>
        prepareSweepAddresses(privateKeys, address, lockTime, gas, gasPrice, utxosLimit).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(preparedTransactions) =>
            submitPreparedSweepTransactions(preparedTransactions, isMiner)
        }
      }
    }

    private def prepareSweepAddresses(
        privateKeys: AVector[ExtendedPrivateKey],
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, AVector[PreparedSweepTransaction]]] = {
      FutureCollection.foldSequentialE(privateKeys)(AVector.empty[PreparedSweepTransaction]) {
        case (transactions, privateKey) =>
          prepareSweepAddress(privateKey, address, lockTime, gas, gasPrice, utxosLimit)
            .map(_.map(transactions ++ _))
      }
    }

    private def prepareSweepAddress(
        privateKey: ExtendedPrivateKey,
        address: Address.Asset,
        lockTime: Option[TimeStamp],
        gas: Option[GasBox],
        gasPrice: Option[GasPrice],
        utxosLimit: Option[Int]
    ): Future[Either[WalletError, AVector[PreparedSweepTransaction]]] = {
      blockFlowClient
        .prepareSweepActiveAddressTransaction(
          privateKey.publicKey,
          address,
          lockTime,
          gas,
          gasPrice,
          utxosLimit
        )
        .flatMap {
          case Left(error) => Future.successful(Left(BlockFlowClientError(error)))
          case Right(buildSweepAllTxResult) =>
            val fromGroup = GroupIndex.unsafe(buildSweepAllTxResult.fromGroup)
            val toGroup   = GroupIndex.unsafe(buildSweepAllTxResult.toGroup)
            Future.successful(
              Right(
                buildSweepAllTxResult.unsignedTxs.map {
                  case SweepAddressTransaction(txId, unsignedTx, _, _) =>
                    val signature = SignatureSchema.sign(txId.bytes, privateKey.privateKey)
                    PreparedSweepTransaction(txId, unsignedTx, signature, fromGroup, toGroup)
                }
              )
            )
        }
    }

    private def submitPreparedSweepTransactions(
        preparedTransactions: AVector[PreparedSweepTransaction],
        isMiner: Boolean
    ): Future[Either[WalletError, AVector[(TransactionId, GroupIndex, GroupIndex)]]] = {
      val queuedTransactions = preparedTransactions.mapWithIndex { case (transaction, index) =>
        QueuedSweepTransaction(index, transaction)
      }
      val lanes =
        if (queuedTransactions.isEmpty) {
          AVector.empty[AVector[QueuedSweepTransaction]]
        } else if (isMiner) {
          AVector.from(
            queuedTransactions
              .groupBy(_.transaction.fromGroup)
              .toSeq
              .sortBy(_._1.value)
              .map(_._2)
          )
        } else {
          AVector(queuedTransactions)
        }

      Future
        .sequence(lanes.toSeq.map(lane => recoverSweepLane(submitSweepLane(lane))))
        .map(collectSweepLaneResults)
    }

    private def collectSweepLaneResults(
        laneResults: Seq[Either[SweepFailure, AVector[SubmittedSweepTransaction]]]
    ): Either[WalletError, AVector[(TransactionId, GroupIndex, GroupIndex)]] = {
      val (submitted, failures) = AVector
        .from(laneResults)
        .fold(
          (
            AVector.empty[SubmittedSweepTransaction],
            AVector.empty[SweepFailure]
          )
        ) {
          case ((submitted, failures), Right(laneSubmitted)) =>
            (submitted ++ laneSubmitted, failures)
          case ((submitted, failures), Left(failure)) =>
            (submitted, failures :+ failure)
        }
      failures.headOption match {
        case None => Right(submitted.sortBy(_.index).map(_.result))
        case Some(firstFailure) =>
          val failedLaneSubmitted = failures.flatMap(_.submitted)
          val possiblySubmitted   = failures.flatMap(_.possiblySubmitted)
          Left(
            SweepFailure(
              firstFailure.cause,
              submitted ++ failedLaneSubmitted,
              possiblySubmitted
            ).toWalletError
          )
      }
    }

    private def blockFlowClientError(error: Throwable): BlockFlowClientError = {
      val message = Option(error.getMessage).getOrElse("BlockFlow request failed")
      BlockFlowClientError(ApiError.InternalServerError(message))
    }

    private def recoverSweepLane(
        result: Future[Either[SweepFailure, AVector[SubmittedSweepTransaction]]]
    ): Future[Either[SweepFailure, AVector[SubmittedSweepTransaction]]] = {
      result.recover { case NonFatal(error) =>
        Left(SweepFailure(blockFlowClientError(error), AVector.empty, AVector.empty))
      }
    }

    private def submitSweepLane(
        transactions: AVector[QueuedSweepTransaction]
    ): Future[Either[SweepFailure, AVector[SubmittedSweepTransaction]]] = {
      val batches = transactions.groupedWithRemainder(sweepBatchSettings.batchSize)

      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def submitNext(
          remaining: AVector[AVector[QueuedSweepTransaction]],
          submitted: AVector[SubmittedSweepTransaction]
      ): Future[Either[SweepFailure, AVector[SubmittedSweepTransaction]]] = {
        remaining.headOption match {
          case None => Future.successful(Right(submitted))
          case Some(batch) =>
            submitSweepBatch(batch).flatMap {
              case Left(failure) => Future.successful(Left(failure.addSubmitted(submitted)))
              case Right(batchResult) =>
                val allSubmitted = submitted ++ batchResult
                if (remaining.length == 1) {
                  Future.successful(Right(allSubmitted))
                } else {
                  waitForSweepBatchConfirmation(batchResult)
                    .recover { case NonFatal(error) => Left(blockFlowClientError(error)) }
                    .flatMap {
                      case Left(error) =>
                        Future.successful(
                          Left(SweepFailure(error, allSubmitted, AVector.empty))
                        )
                      case Right(_) => submitNext(remaining.tail, allSubmitted)
                    }
                }
            }
        }
      }

      submitNext(batches, AVector.empty)
    }

    private def submitSweepBatch(
        batch: AVector[QueuedSweepTransaction]
    ): Future[Either[SweepFailure, AVector[SubmittedSweepTransaction]]] = {
      Future
        .sequence(batch.toSeq.map(submitSweepTransaction))
        .map(collectSweepPostResults)
    }

    private def submitSweepTransaction(
        queued: QueuedSweepTransaction
    ): Future[SweepPostResult] = {
      val transaction = queued.transaction
      blockFlowClient
        .postTransaction(
          transaction.unsignedTx,
          transaction.signature,
          transaction.fromGroup.value
        )
        .map(
          _.map(result =>
            SubmittedSweepTransaction(
              queued.index,
              result.txId,
              transaction.fromGroup,
              transaction.toGroup
            )
          ).left.map(BlockFlowClientError.apply)
        )
        .map(result => SweepPostResult(queued, result, submissionUnknown = false))
        .recover { case NonFatal(error) =>
          SweepPostResult(
            queued,
            Left(blockFlowClientError(error)),
            submissionUnknown = true
          )
        }
    }

    private def collectSweepPostResults(
        results: Seq[SweepPostResult]
    ): Either[SweepFailure, AVector[SubmittedSweepTransaction]] = {
      val (submitted, failed) = AVector
        .from(results)
        .fold(
          (
            AVector.empty[SubmittedSweepTransaction],
            AVector.empty[(QueuedSweepTransaction, WalletError, Boolean)]
          )
        ) {
          case ((submitted, failed), SweepPostResult(_, Right(transaction), _)) =>
            (submitted :+ transaction, failed)
          case ((submitted, failed), SweepPostResult(queued, Left(error), unknown)) =>
            (submitted, failed :+ ((queued, error, unknown)))
        }
      failed.headOption match {
        case None => Right(submitted)
        case Some((_, cause, _)) =>
          val possiblySubmitted = failed.collect { case (queued, _, unknown) =>
            Option.when(unknown)(queued)
          }
          Left(SweepFailure(cause, submitted, possiblySubmitted))
      }
    }

    private def waitForSweepBatchConfirmation(
        transactions: AVector[SubmittedSweepTransaction]
    ): Future[Either[WalletError, Unit]] = {
      val startNanos = System.nanoTime()
      val timeoutNanos =
        TimeUnit.MILLISECONDS.toNanos(sweepBatchSettings.confirmationTimeout.millis)

      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def poll(
          pending: AVector[SubmittedSweepTransaction]
      ): Future[Either[WalletError, Unit]] = {
        if (System.nanoTime() - startNanos >= timeoutNanos) {
          Future.successful(Left(SweepConfirmationTimeout(pending.map(_.txId))))
        } else {
          delay(sweepBatchSettings.confirmationPollInterval).flatMap { _ =>
            fetchPendingSweepTransactions(pending).flatMap {
              case Left(error)                                 => Future.successful(Left(error))
              case Right(stillPending) if stillPending.isEmpty => Future.successful(Right(()))
              case Right(stillPending)                         => poll(stillPending)
            }
          }
        }
      }

      poll(transactions)
    }

    private def fetchPendingSweepTransactions(
        transactions: AVector[SubmittedSweepTransaction]
    ): Future[Either[WalletError, AVector[SubmittedSweepTransaction]]] = {
      Future
        .sequence(transactions.toSeq.map { transaction =>
          blockFlowClient
            .fetchTransactionStatus(transaction.txId, transaction.fromGroup, transaction.toGroup)
            .map(_.map(status => (transaction, status)).left.map(BlockFlowClientError.apply))
        })
        .map { responses =>
          AVector.from(responses).mapE(identity).flatMap { statuses =>
            statuses.foldE(AVector.empty[SubmittedSweepTransaction]) {
              case (pending, (_, _: api.Confirmed)) => Right(pending)
              case (_, (transaction, _: api.Conflicted)) =>
                Left(SweepTransactionConflicted(transaction.txId))
              case (pending, (transaction, _: api.MemPooled))  => Right(pending :+ transaction)
              case (pending, (transaction, _: api.TxNotFound)) => Right(pending :+ transaction)
            }
          }
        }
    }

    private def delay(duration: Duration): Future[Unit] = {
      val promise = Promise[Unit]()
      discard(
        sweepScheduler.schedule(
          new Runnable {
            override def run(): Unit = discard(promise.trySuccess(()))
          },
          duration.millis,
          TimeUnit.MILLISECONDS
        )
      )
      promise.future
    }

    def sign(
        wallet: String,
        data: Hash
    ): Either[WalletError, Signature] = {
      withPrivateKey(wallet) { privateKey =>
        Right(SignatureSchema.sign(data.bytes, privateKey.privateKey))
      }
    }

    override def deriveNextMinerAddresses(
        wallet: String
    ): Either[WalletError, AVector[AddressInfo]] = {
      withMinerWallet(wallet) { secretStorage =>
        secretStorage.getActivePrivateKey() match {
          case Right(activeKey) =>
            for {
              res <- computeNextMinerAddresses(secretStorage)
              _   <- secretStorage.changeActiveKey(activeKey).left.map(_ => UnexpectedError)
            } yield res
          case Left(error) => Left(WalletError.from(error))
        }
      }
    }

    override def deriveNextAddress(
        wallet: String,
        groupOpt: Option[GroupIndex]
    ): Either[WalletError, AddressInfo] = {
      withUserWallet(wallet) { secretStorage =>
        deriveNextAddressFromSecretStorage(secretStorage, groupOpt)
      }
    }

    @tailrec
    private def deriveNextAddressFromSecretStorage(
        secretStorage: SecretStorage,
        groupOpt: Option[GroupIndex]
    ): Either[WalletError, AddressInfo] = {
      secretStorage
        .deriveNextKey()
        .map(AddressInfo.from) match {
        case Left(error) => Left(WalletError.from(error))
        case Right(nextKey) if groupOpt.map(_ == nextKey.group).getOrElse(true) =>
          Right(nextKey)
        case _ => deriveNextAddressFromSecretStorage(secretStorage, groupOpt)
      }
    }

    override def changeActiveAddress(
        wallet: String,
        address: Address.Asset
    ): Either[WalletError, Unit] = {
      withWallet(wallet) { secretStorage =>
        withPrivateKeys(secretStorage) { case (_, privateKeys) =>
          (for {
            privateKey <- privateKeys.find(privateKey =>
              Address.p2pkh(privateKey.publicKey) == address
            )
            _ <- secretStorage.changeActiveKey(privateKey).toOption
          } yield (())).toRight(UnknownAddress(address): WalletError)
        }
      }
    }

    override def listWallets(): Either[WalletError, AVector[(String, Boolean)]] = {
      listWalletsInSecretDir().flatMap { wallets =>
        wallets.mapE { wallet =>
          withWallet(wallet) { secret => Right(secret.isLocked()) }.map { locked =>
            (wallet, locked)
          }
        }
      }
    }

    override def getWallet(wallet: String): Either[WalletError, (String, Boolean)] = {
      withWallet(wallet) { secretStorage =>
        Right((wallet, secretStorage.isLocked()))
      }
    }

    override def revealMnemonic(wallet: String, password: String): Either[WalletError, Mnemonic] = {
      withWallet(wallet) { secretStorage =>
        secretStorage
          .revealMnemonic(password)
          .left
          .map(WalletError.from)
      }
    }

    private def listWalletsInSecretDir(): Either[WalletError, AVector[String]] = {
      val dir = secretDir.toFile
      Either.cond(
        dir.exists && dir.isDirectory,
        AVector.from(dir.listFiles.filter(_.isFile).map(_.getName)),
        UnexpectedError
      )
    }

    private def getBalance(
        address: Address.Asset
    ): Future[Either[WalletError, (Address.Asset, Amount, Amount)]] = {
      blockFlowClient
        .fetchBalance(api.Address.fromProtocol(address))
        .map(
          _.map { case (amount, lockedAmount) =>
            (address, amount, lockedAmount)
          }.left.map(error => BlockFlowClientError(error))
        )
    }

    private def withWalletM[A, M[_]](
        wallet: String
    )(f: SecretStorage => M[A])(errorWrapper: WalletError => M[A]): M[A] = {
      secretStorages.get(wallet) match {
        case None =>
          val file = new File(s"$secretDir/$wallet")
          SecretStorage.load(file, path) match {
            case Right(secretStorage) =>
              secretStorages.addOne(wallet, secretStorage)
              f(secretStorage)
            case Left(error) =>
              errorWrapper(WalletError.from(error))
          }
        case Some(secretStorage) => f(secretStorage)
      }
    }

    private def checkIsMiner(
        storage: SecretStorage,
        isMiner: Boolean
    ): Either[WalletError, Unit] = {
      storage
        .isMiner()
        .left
        .map(WalletError.from)
        .flatMap { state => if (state == isMiner) Right(()) else Left(MinerWalletRequired) }
    }

    private def withWallet[A](
        wallet: String
    )(f: SecretStorage => Either[WalletError, A]): Either[WalletError, A] = {
      withWalletM(wallet)(storage => f(storage))(Left.apply)
    }

    private def withSpecificWallet[A](wallet: String, isMiner: Boolean)(
        f: SecretStorage => Either[WalletError, A]
    ): Either[WalletError, A] = {
      withWalletM(wallet)(storage => checkIsMiner(storage, isMiner).flatMap(_ => f(storage)))(
        Left.apply
      )
    }

    private def withUserWallet[A](
        wallet: String
    )(f: SecretStorage => Either[WalletError, A]): Either[WalletError, A] = {
      withSpecificWallet(wallet, isMiner = false)(f)
    }

    private def withMinerWallet[A](
        wallet: String
    )(f: SecretStorage => Either[WalletError, A]): Either[WalletError, A] = {
      withSpecificWallet(wallet, isMiner = true)(f)
    }

    private def withWalletFut[A](
        wallet: String
    )(f: SecretStorage => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] = {
      withWalletM(wallet)(storage => f(storage))(error => Future.successful(Left(error)))
    }

    private def withPrivateKey[A](
        wallet: String
    )(f: ExtendedPrivateKey => Either[WalletError, A]): Either[WalletError, A] =
      withWallet(wallet)(_.getActivePrivateKey() match {
        case Left(error)       => Left(WalletError.from(error))
        case Right(privateKey) => f(privateKey)
      })

    private def withPrivateKeyFut[A](
        wallet: String
    )(f: ExtendedPrivateKey => Future[Either[WalletError, A]]): Future[Either[WalletError, A]] =
      withWalletFut(wallet)(_.getActivePrivateKey() match {
        case Left(error)       => Future.successful(Left(WalletError.from(error)))
        case Right(privateKey) => f(privateKey)
      })

    private def withSweepPrivateKeysFut[A](wallet: String)(
        f: ((Boolean, AVector[ExtendedPrivateKey])) => Future[Either[WalletError, A]]
    ): Future[Either[WalletError, A]] =
      withWalletFut(wallet) { storage =>
        (for {
          privateKeys <- storage.getAllPrivateKeys()
          isMiner     <- storage.isMiner()
        } yield {
          (privateKeys, isMiner)
        }) match {
          case Left(error) => Future.successful(Left(WalletError.from(error)))
          case Right(((_, privateKeys), isMiner)) =>
            val sweepPrivateKeys =
              if (isMiner) buildMinerPrivateKeys(privateKeys) else privateKeys
            f((isMiner, sweepPrivateKeys))
        }
      }

    private def withPrivateKeysM[A, M[_]](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => M[A]
    )(errorWrapper: WalletError => M[A]): M[A] =
      (for {
        privateKeys <- storage.getAllPrivateKeys()
        isMiner     <- storage.isMiner()
      } yield {
        (privateKeys, isMiner)
      }) match {
        case Left(error) => errorWrapper(WalletError.from(error))
        case Right((privateKeys, isMiner)) =>
          if (isMiner) {
            f((privateKeys._1, buildMinerPrivateKeys(privateKeys._2)))
          } else {
            f(privateKeys)
          }
      }

    private def withAllMinerPrivateKeysM[A, M[_]](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => M[A]
    )(errorWrapper: WalletError => M[A]): M[A] =
      (for {
        _           <- checkIsMiner(storage, true)
        privateKeys <- storage.getAllPrivateKeys().left.map(WalletError.from)
      } yield {
        privateKeys
      }) match {
        case Left(error) => errorWrapper(error)
        case Right(privateKeys) =>
          f(privateKeys)
      }

    private def withPrivateKeys[A](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => Either[WalletError, A]
    ): Either[WalletError, A] =
      withPrivateKeysM(storage)(f)(Left.apply)

    private def withAllMinerPrivateKeys[A](storage: SecretStorage)(
        f: ((ExtendedPrivateKey, AVector[ExtendedPrivateKey])) => Either[WalletError, A]
    ): Either[WalletError, A] =
      withAllMinerPrivateKeysM(storage)(f)(Left.apply)

    private def withAddressesM[A, M[_]](
        wallet: String
    )(
        f: ((Address.Asset, AVector[Address.Asset])) => M[A]
    )(errorWrapper: WalletError => M[A]): M[A] =
      withWalletM(wallet) { storage =>
        withPrivateKeysM(storage) { case (active, privateKeys) =>
          val activeAddress = Address.p2pkh(active.publicKey)
          val addresses =
            privateKeys.map(privateKey => Address.p2pkh(privateKey.publicKey))
          f((activeAddress, addresses))
        }(errorWrapper)
      }(errorWrapper)

    private def withAddressesFut[A](wallet: String)(
        f: ((Address.Asset, AVector[Address.Asset])) => Future[Either[WalletError, A]]
    ): Future[Either[WalletError, A]] =
      withAddressesM(wallet)(f)(error => Future.successful(Left(error)))

    private def buildWalletFile(walletName: String): Either[WalletError, File] = {
      val regex = "^[a-zA-Z0-9_-]*$".r
      Either.cond(
        regex.matches(walletName),
        new File(s"$secretDir/$walletName"),
        InvalidWalletName(walletName)
      )
    }

    private def buildMinerAddresses(
        privateKeys: AVector[ExtendedPrivateKey]
    ): AVector[AVector[(AddressInfo, ExtendedPrivateKey)]] = {
      val addresses: AVector[(AddressInfo, ExtendedPrivateKey)] =
        privateKeys.map { privateKey =>
          val addressInfo = AddressInfo.from(privateKey)
          (addressInfo, privateKey)
        }

      val addressByGroup = addresses.toSeq.groupBy(_._1.group)
      val smallestIndex  = addressByGroup.values.map(_.length).minOption.getOrElse(1)

      var res: Seq[Seq[(GroupIndex, (AddressInfo, ExtendedPrivateKey))]]    = Seq.empty
      var addForGroup: Seq[(GroupIndex, (AddressInfo, ExtendedPrivateKey))] = Seq.empty
      (0 until smallestIndex).foreach { index =>
        (0 until groupConfig.groups).foreach { group =>
          val groupIndex = GroupIndex.unsafe(group)
          addressByGroup.get(groupIndex).flatMap(_.lift(index)).foreach { address =>
            addForGroup = addForGroup :+ ((groupIndex, address))
          }
        }
        res = res :+ addForGroup
        addForGroup = Seq.empty
      }
      AVector.from(
        res.map(l =>
          AVector.from(l.map { case (_, (address, privateKey)) =>
            (address, privateKey)
          })
        )
      )
    }

    private def buildMinerPrivateKeys(
        privateKeys: AVector[ExtendedPrivateKey]
    ): AVector[ExtendedPrivateKey] = {
      buildMinerAddresses(privateKeys).flatMap(_.map { case (_, privateKey) => privateKey })
    }

    private def computeNextMinerAddresses(
        storage: SecretStorage
    ): Either[WalletError, AVector[AddressInfo]] = {
      withAllMinerPrivateKeys(storage) { case (_, keys) =>
        val addressByGroup = keys.toSeq
          .map(AddressInfo.from)
          .groupBy(_.group)

        if (addressByGroup.keys.size < groupConfig.groups) {
          computeNextMinerAddressesWithIndex(addressByGroup, storage, 0)
        } else {
          val smallestIndex = addressByGroup.values.map(_.length).minOption.getOrElse(0)
          computeNextMinerAddressesWithIndex(addressByGroup, storage, smallestIndex)
        }
      }
    }

    @tailrec
    private def computeNextMinerAddressesWithIndex(
        addressByGroup: Map[GroupIndex, Seq[AddressInfo]],
        storage: SecretStorage,
        indexWanted: Int
    ): Either[WalletError, AVector[AddressInfo]] = {
      val addresses = addressByGroup.values.map(_.lift(indexWanted))
      if (addressByGroup.keys.size < groupConfig.groups || addresses.exists(_.isEmpty)) {
        storage
          .deriveNextKey() match {
          case Left(error) => Left(WalletError.from(error))
          case Right(nextKey) =>
            computeNextMinerAddressesWithIndex(
              updateAddressByGroup(addressByGroup, nextKey),
              storage,
              indexWanted
            )
        }
      } else {
        Right(AVector.from(addresses.flatten))
      }
    }

    private def updateAddressByGroup(
        addressByGroup: Map[GroupIndex, Seq[AddressInfo]],
        privateKey: ExtendedPrivateKey
    ): Map[GroupIndex, Seq[AddressInfo]] = {
      val address = AddressInfo.from(privateKey)
      val group   = address.group
      val newKeys = addressByGroup.get(group) match {
        case None       => Seq(address)
        case Some(keys) => keys :+ address
      }
      addressByGroup + ((group, newKeys))
    }
  }
}
