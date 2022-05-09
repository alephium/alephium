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

package org.alephium.flow.handler

import scala.collection.mutable

import akka.actor.Props

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.{PendingTxStorage, ReadyTxStorage}
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.{MiningBlob, PersistedTxId, ReadyTxInfo}
import org.alephium.flow.network.InterCliqueManager
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.sync.FetchState
import org.alephium.flow.setting.{MemPoolSetting, NetworkSetting}
import org.alephium.flow.validation._
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, LogConfig}
import org.alephium.serde.serialize
import org.alephium.util._

object TxHandler {
  def props(
      blockFlow: BlockFlow,
      pendingTxStorage: PendingTxStorage,
      readyTxStorage: ReadyTxStorage
  )(implicit
      brokerConfig: BrokerConfig,
      memPoolSetting: MemPoolSetting,
      networkSetting: NetworkSetting,
      logConfig: LogConfig
  ): Props =
    Props(new TxHandler(blockFlow, pendingTxStorage, readyTxStorage))

  sealed trait Command
  final case class AddToSharedPool(txs: AVector[TransactionTemplate])            extends Command
  final case class Broadcast(txs: AVector[(TransactionTemplate, TimeStamp)])     extends Command
  final case class AddToGrandPool(txs: AVector[TransactionTemplate])             extends Command
  final case class TxAnnouncements(hashes: AVector[(ChainIndex, AVector[Hash])]) extends Command
  case object CleanSharedPool                                                    extends Command
  case object CleanPendingPool                                                   extends Command
  private case object BroadcastTxs                                               extends Command
  private case object DownloadTxs                                                extends Command

  sealed trait Event
  final case class AddSucceeded(txId: Hash)              extends Event
  final case class AddFailed(txId: Hash, reason: String) extends Event

  final case class Announcement(
      brokerHandler: ActorRefT[BrokerHandler.Command],
      chainIndex: ChainIndex,
      hash: Hash
  )

  val MaxDownloadTimes: Int = 2
  // scalastyle:off magic.number
  val PersistenceDuration: Duration = Duration.ofSecondsUnsafe(30)
  // scalastyle:on magic.number

  // scalastyle:off magic.number
  private val highPriceUntil: TimeStamp =
    ALPH.LaunchTimestamp.plusUnsafe(Duration.ofDaysUnsafe(365))
  // scalastyle:off magic.number
  def checkHighGasPrice(tx: TransactionTemplate): Boolean = {
    checkHighGasPrice(TimeStamp.now(), tx)
  }
  @inline def checkHighGasPrice(currentTs: TimeStamp, tx: TransactionTemplate): Boolean = {
    if (currentTs <= highPriceUntil) {
      tx.unsigned.gasPrice >= defaultGasPrice
    } else {
      true
    }
  }

  def mineTxForDev(blockFlow: BlockFlow, txTemplate: TransactionTemplate)(implicit
      groupConfig: GroupConfig
  ): Either[String, Unit] = {
    val chainIndex = txTemplate.chainIndex
    val memPool    = blockFlow.getMemPool(chainIndex)
    memPool.addNewTx(chainIndex, txTemplate, TimeStamp.now()) match {
      case MemPool.AddedToSharedPool =>
        for {
          _ <- mineTxForDev(blockFlow, chainIndex)
          _ <-
            if (!chainIndex.isIntraGroup) {
              mineTxForDev(blockFlow, ChainIndex(chainIndex.from, chainIndex.from))
            } else {
              Right(())
            }
        } yield ()
      case _ =>
        memPool.clear()
        Left("Unable to add the tx the mempool: maybe the parent tx is not confirmed")
    }
  }

  def mineTxForDev(blockFlow: BlockFlow, chainIndex: ChainIndex)(implicit
      groupConfig: GroupConfig
  ): Either[String, Unit] = {
    val memPool = blockFlow.getMemPool(chainIndex)
    try {
      val (_, minerPubKey) = chainIndex.to.generateKey
      val miner            = LockupScript.p2pkh(minerPubKey)
      val flowTemplate     = blockFlow.prepareBlockFlowUnsafe(chainIndex, miner)
      val miningBlob       = MiningBlob.from(flowTemplate)
      val block            = Miner.mineForDev(chainIndex, miningBlob)
      validateAndAddBlock(blockFlow, block)
    } catch {
      case error: Throwable =>
        Left(error.getMessage)
    } finally {
      memPool.clear()
    }
  }

  private def validateAndAddBlock(blockFlow: BlockFlow, block: Block): Either[String, Unit] = {
    val blockValidator = BlockValidation.build(blockFlow)
    blockValidator.validate(block, blockFlow) match {
      case Left(error) => Left(s"Failed in validating the mined block: $error")
      case Right(worldStateOpt) =>
        blockFlow.add(block, worldStateOpt) match {
          case Left(error) => Left(s"Failed in add the mined block: $error")
          case Right(_) =>
            blockFlow.updateBestDepsUnsafe()
            Right(())
        }
    }
  }
}

class TxHandler(
    blockFlow: BlockFlow,
    pendingTxStorage: PendingTxStorage,
    readyTxStorage: ReadyTxStorage
)(implicit
    brokerConfig: BrokerConfig,
    memPoolSetting: MemPoolSetting,
    networkSetting: NetworkSetting,
    logConfig: LogConfig
) extends IOBaseActor
    with EventStream.Publisher
    with InterCliqueManager.NodeSyncStatus {
  private val nonCoinbaseValidation = TxValidation.build
  val maxCapacity: Int              = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32
  val fetching: FetchState[Hash] =
    FetchState[Hash](maxCapacity, networkSetting.syncExpiryPeriod, TxHandler.MaxDownloadTimes)
  val txsBuffer: Cache[TransactionTemplate, Unit] =
    Cache.fifo[TransactionTemplate, Unit](maxCapacity)
  val delayedTxs: Cache[TransactionTemplate, TimeStamp] =
    Cache.fifo[TransactionTemplate, TimeStamp](maxCapacity)
  val announcements: Cache[TxHandler.Announcement, Unit] =
    Cache.fifo[TxHandler.Announcement, Unit](maxCapacity)

  override def receive: Receive = handleCommand orElse updateNodeSyncStatus

  // scalastyle:off method.length
  def handleCommand: Receive = {
    case TxHandler.AddToSharedPool(txs) =>
      if (!memPoolSetting.autoMineForDev) {
        txs.foreach(
          handleTx(_, nonCoinbaseValidation.validateMempoolTxTemplate, None, acknowledge = true)
        )
      } else {
        mineTxsForDev(txs)
      }
    case TxHandler.AddToGrandPool(txs) =>
      if (!memPoolSetting.autoMineForDev) {
        txs.foreach(
          handleTx(_, nonCoinbaseValidation.validateGrandPoolTxTemplate, None, acknowledge = true)
        )
      } else {
        mineTxsForDev(txs)
      }
    case TxHandler.Broadcast(txs)          => handleReadyTxsFromPendingPool(txs)
    case TxHandler.TxAnnouncements(hashes) => handleAnnouncements(hashes)
    case TxHandler.BroadcastTxs            => broadcastTxs()
    case TxHandler.DownloadTxs             => downloadTxs()
    case TxHandler.CleanSharedPool =>
      log.debug("Start to clean shared pools")
      val results = blockFlow.grandPool.cleanAndExtractReadyTxs(
        blockFlow,
        TimeStamp.now().minusUnsafe(memPoolSetting.cleanSharedPoolFrequency)
      )
      results.foreach { result =>
        result.invalidTxss.foreach(escapeIOError(_)(_.foreach { tx =>
          escapeIOError(readyTxStorage.getOpt(tx.id).flatMap {
            case Some(info) =>
              val persistedTxId = PersistedTxId(info.timestamp, tx.id)
              readyTxStorage.delete(tx.id).flatMap(_ => pendingTxStorage.delete(persistedTxId))
            case None =>
              Right(())
          })
        }))
        handleReadyTxsFromPendingPool(result.readyTxs)
      }
    case TxHandler.CleanPendingPool =>
      log.debug("Start to clean pending pools")
      blockFlow.grandPool.cleanPendingPool(blockFlow).foreach { result =>
        escapeIOError(result) { invalidPendingTxs =>
          invalidPendingTxs.foreach { case (tx, timestamp) =>
            val persistedTxId = PersistedTxId(timestamp, tx.id)
            escapeIOError(pendingTxStorage.delete(persistedTxId))
          }
        }
      }
      removeConfirmedTxsFromStorage()
  }

  def mineTxsForDev(txs: AVector[TransactionTemplate]): Unit = {
    txs.foreach { tx =>
      TxHandler.mineTxForDev(blockFlow, tx) match {
        case Left(error) => addFailed(tx, error)
        case Right(_)    => addSucceeded(tx)
      }
    }
  }

  private def removeConfirmedTxsFromStorage(): Unit = {
    escapeIOError(readyTxStorage.iterateE { (hash, info) =>
      blockFlow.isTxConfirmed(hash, info.chainIndex).flatMap { confirmed =>
        if (confirmed) {
          val persistedTxId = PersistedTxId(info.timestamp, hash)
          readyTxStorage.delete(hash).flatMap(_ => pendingTxStorage.delete(persistedTxId))
        } else {
          Right(())
        }
      }
    })
  }

  private def handleReadyTxsFromPendingPool(
      txs: AVector[(TransactionTemplate, TimeStamp)]
  ): Unit = {
    // delay this broadcast so that peers have download this block
    val broadcastTs = TimeStamp.now().plusUnsafe(networkSetting.txsBroadcastDelay)
    txs.foreach { case (tx, timestamp) =>
      delayedTxs.put(tx, broadcastTs)
      val info = ReadyTxInfo(tx.chainIndex, timestamp)
      escapeIOError(readyTxStorage.put(tx.id, info))
    }
  }

  override def onFirstTimeSynced(): Unit = {
    clearStorageAndLoadTxs()
    schedule(self, TxHandler.CleanSharedPool, memPoolSetting.cleanSharedPoolFrequency)
    schedule(self, TxHandler.CleanPendingPool, memPoolSetting.cleanPendingPoolFrequency)
    scheduleOnce(self, TxHandler.BroadcastTxs, memPoolSetting.batchBroadcastTxsFrequency)
    scheduleOnce(self, TxHandler.DownloadTxs, memPoolSetting.batchDownloadTxsFrequency)
  }

  def clearStorageAndLoadTxs(): Unit = {
    escapeIOError(readyTxStorage.clear())
    log.info("Start to load persisted pending txs")
    var (valid, invalid) = (0, 0)
    escapeIOError(for {
      groupViews <- AVector
        .tabulateE(brokerConfig.groupNumPerBroker) { index =>
          val groupIndex = GroupIndex.unsafe(brokerConfig.groupRange(index))
          blockFlow.getImmutableGroupViewIncludePool(groupIndex)
        }
      _ <- pendingTxStorage.iterateE { (persistedTxId, tx) =>
        val chainIndex = tx.chainIndex
        val groupIndex = chainIndex.from
        val index      = brokerConfig.groupIndexOfBroker(groupIndex)
        groupViews(index).getPreOutputs(tx.unsigned.inputs).flatMap {
          case Some(_) =>
            valid += 1
            handleTx(
              tx,
              nonCoinbaseValidation.validateGrandPoolTxTemplate,
              Some(persistedTxId),
              acknowledge = false
            )
            Right(())
          case None =>
            invalid += 1
            pendingTxStorage.delete(persistedTxId)
        }
      }
    } yield ())
    log.info(
      s"Load persisted pending txs completed, valid: #$valid, invalid: #$invalid (not precise though..)"
    )
  }

  private def downloadTxs(): Unit = {
    log.debug("Start to download txs")
    if (announcements.nonEmpty) {
      val downloads = mutable.Map
        .empty[ActorRefT[BrokerHandler.Command], mutable.Map[ChainIndex, mutable.ArrayBuffer[Hash]]]
      announcements.keys().foreach { announcement =>
        downloads.get(announcement.brokerHandler) match {
          case Some(chainIndexedHashes) =>
            updateChainIndexHashes(announcement.hash, announcement.chainIndex, chainIndexedHashes)
          case None =>
            val hashes             = mutable.ArrayBuffer(announcement.hash)
            val chainIndexedHashes = mutable.Map(announcement.chainIndex -> hashes)
            downloads(announcement.brokerHandler) = chainIndexedHashes
        }
      }
      downloads.foreach { case (brokerHandler, chainIndexedHashes) =>
        val txHashes = chainIndexedHashesToAVector(chainIndexedHashes)
        log.debug(s"Download tx announcements ${Utils.showChainIndexedDigest(txHashes)}")
        brokerHandler ! BrokerHandler.DownloadTxs(txHashes)
      }
      announcements.clear()
    }
    scheduleOnce(self, TxHandler.DownloadTxs, memPoolSetting.batchDownloadTxsFrequency)
  }

  private def handleAnnouncements(hashes: AVector[(ChainIndex, AVector[Hash])]): Unit = {
    val timestamp     = TimeStamp.now()
    val brokerHandler = ActorRefT[BrokerHandler.Command](sender())
    hashes.foreach { case (chainIndex, hashes) =>
      val mempool = blockFlow.getMemPool(chainIndex)
      hashes.foreach { hash =>
        if (
          fetching.needToFetch(hash, timestamp) &&
          !mempool.contains(chainIndex, hash)
        ) {
          val announcement = TxHandler.Announcement(brokerHandler, chainIndex, hash)
          announcements.put(announcement, ())
        }
      }
    }
  }

  private def hex(tx: TransactionTemplate): String = {
    Hex.toHexString(serialize(tx))
  }

  def handleTx(
      tx: TransactionTemplate,
      validate: (TransactionTemplate, BlockFlow) => TxValidationResult[Unit],
      persistedTxIdOpt: Option[PersistedTxId],
      acknowledge: Boolean
  ): Unit = {
    val chainIndex = tx.chainIndex
    val mempool    = blockFlow.getMemPool(chainIndex)
    if (!TxHandler.checkHighGasPrice(tx)) {
      addFailed(tx, s"tx has lower gas price than ${defaultGasPrice}")
    } else if (mempool.contains(chainIndex, tx)) {
      addFailed(tx, s"tx ${tx.id.toHexString} is already included")
    } else if (mempool.isDoubleSpending(chainIndex, tx)) {
      addFailed(tx, s"tx ${tx.id.shortHex} is double spending: ${hex(tx)}")
    } else {
      validate(tx, blockFlow) match {
        case Left(Right(s: InvalidTxStatus)) =>
          addFailed(tx, s"Failed in validating tx ${tx.id.toHexString} due to $s: ${hex(tx)}")
        case Right(_) =>
          handleValidTx(chainIndex, tx, mempool, persistedTxIdOpt, acknowledge)
        case Left(Left(e)) =>
          addFailed(tx, s"IO failed in validating tx ${tx.id.toHexString} due to $e: ${hex(tx)}")
      }
    }
  }

  private def needToDelay(chainIndex: ChainIndex, tx: TransactionTemplate): Boolean = {
    val outputOpt =
      blockFlow.getBestPersistedWorldState(chainIndex.from).flatMap { persistedWS =>
        persistedWS.getPreOutputsForAssetInputs(tx).map(_.map(_.maxBy(_.lockTime)))
      }

    escapeIOError[Option[AssetOutput], Boolean](
      outputOpt,
      {
        case Some(output) =>
          output.lockTime.plusUnsafe(TxHandler.PersistenceDuration) > TimeStamp.now()
        case None => true // some of the inputs are from block caches outputs
      }
    )(false)
  }

  private def handleValidTx(
      chainIndex: ChainIndex,
      tx: TransactionTemplate,
      mempool: MemPool,
      persistedTxIdOpt: Option[PersistedTxId],
      acknowledge: Boolean
  ): Unit = {
    val currentTs = TimeStamp.now()
    val result    = mempool.addNewTx(chainIndex, tx, currentTs)
    log.debug(s"Add tx ${tx.id.shortHex} for $chainIndex, type: $result")
    result match {
      case MemPool.AddedToSharedPool =>
        if (needToDelay(chainIndex, tx)) {
          delayedTxs.put(tx, currentTs.plusUnsafe(networkSetting.txsBroadcastDelay))
        } else {
          txsBuffer.put(tx, ())
        }
        persistedTxIdOpt.foreach { persistedTxId =>
          val info = ReadyTxInfo(chainIndex, persistedTxId.timestamp)
          escapeIOError(readyTxStorage.put(tx.id, info))
        }
      case MemPool.AddedToPendingPool => // We don't broadcast txs that are pending locally
        val newPersistedTxId = PersistedTxId(currentTs, tx.id)
        persistedTxIdOpt match {
          case Some(oldPersistedTxId) =>
            escapeIOError(pendingTxStorage.replace(oldPersistedTxId, newPersistedTxId, tx))
          case None =>
            escapeIOError(pendingTxStorage.put(newPersistedTxId, tx))
        }
      case _ => ()
    }
    if (acknowledge) {
      addSucceeded(tx)
    }
  }

  private def updateChainIndexHashes(
      hash: Hash,
      chainIndex: ChainIndex,
      hashes: mutable.Map[ChainIndex, mutable.ArrayBuffer[Hash]]
  ): Unit = {
    hashes.get(chainIndex) match {
      case Some(chainIndexHashes) => chainIndexHashes += hash
      case None =>
        val chainIndexedHashes = mutable.ArrayBuffer(hash)
        hashes(chainIndex) = chainIndexedHashes
    }
  }

  private def chainIndexedHashesToAVector(
      hashes: mutable.Map[ChainIndex, mutable.ArrayBuffer[Hash]]
  ): AVector[(ChainIndex, AVector[Hash])] = {
    hashes.foldLeft(AVector.empty[(ChainIndex, AVector[Hash])]) {
      case (acc, (chainIndex, hashes)) =>
        acc :+ (chainIndex -> AVector.from(hashes))
    }
  }

  private def broadcastTxs(): Unit = {
    log.debug("Start to broadcast txs")
    val broadcasts = mutable.Map.empty[ChainIndex, mutable.ArrayBuffer[Hash]]
    if (txsBuffer.nonEmpty) {
      txsBuffer.keys().foreach { tx =>
        updateChainIndexHashes(tx.id, tx.chainIndex, broadcasts)
      }
      txsBuffer.clear()
    }

    if (delayedTxs.nonEmpty) {
      val currentTs = TimeStamp.now()
      val removed   = mutable.ArrayBuffer.empty[TransactionTemplate]
      delayedTxs
        .entries()
        .takeWhile(_.getValue <= currentTs)
        .foreach { entry =>
          val tx = entry.getKey
          removed += tx
          updateChainIndexHashes(tx.id, tx.chainIndex, broadcasts)
        }
      removed.foreach(delayedTxs.remove)
    }

    if (broadcasts.nonEmpty) {
      val txHashes = chainIndexedHashesToAVector(broadcasts)
      publishEvent(InterCliqueManager.BroadCastTx(txHashes))
    }
    scheduleOnce(self, TxHandler.BroadcastTxs, memPoolSetting.batchBroadcastTxsFrequency)
  }

  def addSucceeded(tx: TransactionTemplate): Unit = {
    sender() ! TxHandler.AddSucceeded(tx.id)
  }

  def addFailed(tx: TransactionTemplate, reason: String): Unit = {
    sender() ! TxHandler.AddFailed(tx.id, reason: String)
  }
}
