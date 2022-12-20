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
import scala.reflect.ClassTag

import akka.actor.Props

import org.alephium.flow.Utils
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.io.{PendingTxStorage, ReadyTxStorage}
import org.alephium.flow.mempool.{GrandPool, MemPool}
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.{DataOrigin, MiningBlob, PersistedTxId, ReadyTxInfo}
import org.alephium.flow.network.{InterCliqueManager, IntraCliqueManager}
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.sync.FetchState
import org.alephium.flow.setting.{MemPoolSetting, NetworkSetting}
import org.alephium.flow.validation._
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.message.{Message, NewBlock}
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
  final case class AddToMemPool(txs: AVector[TransactionTemplate], isIntraCliqueSyncing: Boolean)
      extends Command
  final case class TxAnnouncements(txs: AVector[(ChainIndex, AVector[TransactionId])])
      extends Command
  final case class MineOneBlock(chainIndex: ChainIndex) extends Command
  case object CleanMemPool                              extends Command
  case object CleanPendingPool                          extends Command
  private case object BroadcastTxs                      extends Command
  private case object DownloadTxs                       extends Command

  sealed trait Event
  final case class AddSucceeded(txId: TransactionId)              extends Event
  final case class AddFailed(txId: TransactionId, reason: String) extends Event

  final case class Announcement(
      brokerHandler: ActorRefT[BrokerHandler.Command],
      chainIndex: ChainIndex,
      hash: TransactionId
  )

  val MaxDownloadTimes: Int = 2
  // scalastyle:off magic.number
  val PersistenceDuration: Duration = Duration.ofSecondsUnsafe(30)
  // scalastyle:on magic.number

  // scalastyle:off magic.number
  private val highPriceUntil: TimeStamp =
    ALPH.LaunchTimestamp.plusUnsafe(ALPH.OneAndHalfYear)
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

  def mineTxForDev(
      blockFlow: BlockFlow,
      txTemplate: TransactionTemplate,
      publishBlock: Block => Unit
  )(implicit
      groupConfig: GroupConfig
  ): Either[String, Unit] = {
    val chainIndex = txTemplate.chainIndex
    val grandPool  = blockFlow.getGrandPool()
    grandPool.add(chainIndex, txTemplate, TimeStamp.now()) match {
      case MemPool.AddedToMemPool =>
        for {
          _ <- mineTxForDev(blockFlow, chainIndex, publishBlock)
          _ <-
            if (!chainIndex.isIntraGroup) {
              mineTxForDev(blockFlow, ChainIndex(chainIndex.from, chainIndex.from), publishBlock)
            } else {
              Right(())
            }
        } yield ()
      case _ =>
        Left("Unable to add the tx the mempool: maybe the parent tx is not confirmed")
    }
  }

  def mineTxForDev(blockFlow: BlockFlow, chainIndex: ChainIndex, publishBlock: Block => Unit)(
      implicit groupConfig: GroupConfig
  ): Either[String, Unit] = {
    val memPool          = blockFlow.getMemPool(chainIndex)
    val (_, minerPubKey) = chainIndex.to.generateKey
    val miner            = LockupScript.p2pkh(minerPubKey)
    val result = for {
      flowTemplate <- blockFlow.prepareBlockFlow(chainIndex, miner).left.map(_.getMessage)
      miningBlob = MiningBlob.from(flowTemplate)
      block      = Miner.mineForDev(chainIndex, miningBlob)
      _ <- validateAndAddBlock(blockFlow, block)
    } yield {
      publishBlock(block)
    }
    if (result.isLeft) {
      memPool.clear()
    }
    result
  }

  def forceMineForDev(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex,
      env: Env,
      publishBlock: Block => Unit
  )(implicit
      groupConfig: GroupConfig,
      memPoolSetting: MemPoolSetting
  ): Either[String, Unit] = {
    if (env != Env.Prod || memPoolSetting.autoMineForDev) {
      mineTxForDev(blockFlow, chainIndex, publishBlock)
    } else {
      Left(
        "CPU mining for dev is not enabled, please turn it on in config:\n alephium.mempool.auto-mine-for-dev = true"
      )
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
    val blockFlow: BlockFlow,
    val pendingTxStorage: PendingTxStorage,
    val readyTxStorage: ReadyTxStorage
)(implicit
    brokerConfig: BrokerConfig,
    memPoolSetting: MemPoolSetting,
    networkSetting: NetworkSetting,
    logConfig: LogConfig
) extends IOBaseActor
    with EventStream.Publisher
    with InterCliqueManager.NodeSyncStatus {
  private val nonCoinbaseValidation = TxValidation.build
  val maxCapacity: Int = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32
  val fetching: FetchState[TransactionId] =
    FetchState[TransactionId](
      maxCapacity,
      networkSetting.syncExpiryPeriod,
      TxHandler.MaxDownloadTimes
    )
  val txsBuffer: Cache[TransactionTemplate, Unit] =
    Cache.fifo[TransactionTemplate, Unit](maxCapacity)
  val delayedTxs: Cache[TransactionTemplate, TimeStamp] =
    Cache.fifo[TransactionTemplate, TimeStamp](maxCapacity)
  val announcements: Cache[TxHandler.Announcement, Unit] =
    Cache.fifo[TxHandler.Announcement, Unit](maxCapacity)

  override def receive: Receive = handleCommand orElse updateNodeSyncStatus

  type TxsPerChain[T] = mutable.Map[ChainIndex, mutable.ArrayBuffer[T]]

  // scalastyle:off method.length
  def handleCommand: Receive = {
    case TxHandler.AddToMemPool(txs, isIntraCliqueSyncing) =>
      if (!memPoolSetting.autoMineForDev) {
        if (isIntraCliqueSyncing) {
          txs.foreach(handleIntraCliqueSyncingTx)
        } else {
          txs.foreach(
            handleInterCliqueTx(
              _,
              nonCoinbaseValidation.validateMempoolTxTemplate,
              None,
              acknowledge = true
            )
          )
        }
      } else {
        mineTxsForDev(txs)
      }
    case TxHandler.TxAnnouncements(txs) => handleAnnouncements(txs)
    case TxHandler.BroadcastTxs         => broadcastTxs()
    case TxHandler.DownloadTxs          => downloadTxs()
    case TxHandler.MineOneBlock(chainIndex) =>
      TxHandler
        .forceMineForDev(blockFlow, chainIndex, Env.currentEnv, publishBlock)
        .swap
        .foreach(log.error(_))
    case TxHandler.CleanMemPool =>
      log.debug("Start to clean mempools")
      blockFlow.grandPool.clean(
        blockFlow,
        TimeStamp.now().minusUnsafe(memPoolSetting.cleanMempoolFrequency)
      )
  }

  def mineTxsForDev(txs: AVector[TransactionTemplate]): Unit = {
    txs.foreach { tx =>
      TxHandler.mineTxForDev(blockFlow, tx, publishBlock) match {
        case Left(error) => addFailed(tx, error)
        case Right(_)    => addSucceeded(tx)
      }
    }
  }

  def publishBlock(block: Block): Unit = {
    val blockMessage = Message.serialize(NewBlock(block))
    val event        = InterCliqueManager.BroadCastBlock(block, blockMessage, DataOrigin.Local)
    publishEvent(event)
  }

  override def onFirstTimeSynced(): Unit = {
    clearStorageAndLoadTxs()
    schedule(self, TxHandler.CleanMemPool, memPoolSetting.cleanMempoolFrequency)
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
            handleInterCliqueTx(
              tx,
              nonCoinbaseValidation.validateMempoolTxTemplate,
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
      val downloads =
        mutable.Map.empty[ActorRefT[BrokerHandler.Command], TxsPerChain[TransactionId]]
      announcements.keys().foreach { announcement =>
        downloads.get(announcement.brokerHandler) match {
          case Some(chainIndexedHashes) =>
            updateChainIndexTxs(announcement.hash, announcement.chainIndex, chainIndexedHashes)
          case None =>
            val hashes             = mutable.ArrayBuffer(announcement.hash)
            val chainIndexedHashes = mutable.Map(announcement.chainIndex -> hashes)
            downloads(announcement.brokerHandler) = chainIndexedHashes
        }
      }
      downloads.foreach { case (brokerHandler, chainIndexedHashes) =>
        val txHashes = chainIndexedTxsToAVector(chainIndexedHashes)
        log.debug(s"Download tx announcements ${Utils.showChainIndexedDigest(txHashes)}")
        brokerHandler ! BrokerHandler.DownloadTxs(txHashes)
      }
      announcements.clear()
    }
    scheduleOnce(self, TxHandler.DownloadTxs, memPoolSetting.batchDownloadTxsFrequency)
  }

  private def handleAnnouncements(txs: AVector[(ChainIndex, AVector[TransactionId])]): Unit = {
    val timestamp     = TimeStamp.now()
    val brokerHandler = ActorRefT[BrokerHandler.Command](sender())
    txs.foreach { case (chainIndex, txs) =>
      val mempool = blockFlow.getMemPool(chainIndex)
      txs.foreach { tx =>
        if (fetching.needToFetch(tx, timestamp) && !mempool.contains(tx)) {
          val announcement = TxHandler.Announcement(brokerHandler, chainIndex, tx)
          announcements.put(announcement, ())
        }
      }
    }
  }

  private def hex(tx: TransactionTemplate): String = {
    Hex.toHexString(serialize(tx))
  }

  def handleInterCliqueTx(
      tx: TransactionTemplate,
      validate: (TransactionTemplate, BlockFlow) => TxValidationResult[Unit],
      persistedTxIdOpt: Option[PersistedTxId],
      acknowledge: Boolean
  ): Unit = {
    val chainIndex = tx.chainIndex
    assume(!brokerConfig.isIncomingChain(chainIndex))
    val mempool = blockFlow.getMemPool(chainIndex.from)
    if (!TxHandler.checkHighGasPrice(tx)) {
      addFailed(tx, s"tx has lower gas price than ${defaultGasPrice}")
    } else if (mempool.contains(tx)) {
      addFailed(tx, s"tx ${tx.id.toHexString} is already included")
    } else if (mempool.isDoubleSpending(chainIndex, tx)) {
      addFailed(tx, s"tx ${tx.id.shortHex} is double spending: ${hex(tx)}")
    } else {
      validate(tx, blockFlow) match {
        case Left(Right(s: InvalidTxStatus)) =>
          addFailed(tx, s"Failed in validating tx ${tx.id.toHexString} due to $s: ${hex(tx)}")
        case Right(_) =>
          val grandPool = blockFlow.getGrandPool()
          handleValidTx(chainIndex, tx, grandPool, persistedTxIdOpt, acknowledge)
        case Left(Left(e)) =>
          addFailed(tx, s"IO failed in validating tx ${tx.id.toHexString} due to $e: ${hex(tx)}")
      }
    }
  }

  def handleIntraCliqueSyncingTx(tx: TransactionTemplate): Unit = {
    val chainIndex = tx.chainIndex
    assume(brokerConfig.isIncomingChain(chainIndex))
    blockFlow.getMemPool(chainIndex.to).addXGroupTx(chainIndex, tx, TimeStamp.now())
  }

  private def handleValidTx(
      chainIndex: ChainIndex,
      tx: TransactionTemplate,
      grandPool: GrandPool,
      persistedTxIdOpt: Option[PersistedTxId],
      acknowledge: Boolean
  ): Unit = {
    val currentTs = TimeStamp.now()
    val result    = grandPool.add(chainIndex, tx, currentTs)
    log.debug(s"Add tx ${tx.id.shortHex} for $chainIndex, type: $result")
    result match {
      case MemPool.AddedToMemPool =>
        txsBuffer.put(tx, ())
        persistedTxIdOpt.foreach { persistedTxId =>
          val info = ReadyTxInfo(chainIndex, persistedTxId.timestamp)
          escapeIOError(readyTxStorage.put(tx.id, info))
        }
      case _ => ()
    }
    if (acknowledge) {
      addSucceeded(tx)
    }
  }

  private def updateChainIndexTxs[T](
      tx: T,
      chainIndex: ChainIndex,
      txs: TxsPerChain[T]
  ): Unit = {
    txs.get(chainIndex) match {
      case Some(chainIndexHashes) => chainIndexHashes += tx
      case None =>
        val chainIndexedHashes = mutable.ArrayBuffer(tx)
        txs(chainIndex) = chainIndexedHashes
    }
  }

  private def chainIndexedTxsToAVector[T: ClassTag](
      txs: TxsPerChain[T]
  ): AVector[(ChainIndex, AVector[T])] = {
    txs.foldLeft(AVector.empty[(ChainIndex, AVector[T])]) { case (acc, (chainIndex, txs)) =>
      acc :+ (chainIndex -> AVector.from(txs))
    }
  }

  private def broadcastTxs(): Unit = {
    log.debug("Start to broadcast txs")
    val broadcasts = mutable.Map.empty[ChainIndex, mutable.ArrayBuffer[TransactionTemplate]]
    if (txsBuffer.nonEmpty) {
      txsBuffer.keys().foreach { tx =>
        updateChainIndexTxs(tx, tx.chainIndex, broadcasts)
      }
      txsBuffer.clear()
    }

    if (broadcasts.nonEmpty) {
      val txs = chainIndexedTxsToAVector(broadcasts)
      publishEvent(InterCliqueManager.BroadCastTx(txs.map(p => p._1 -> p._2.map(_.id))))
      if (brokerConfig.brokerNum > 1) {
        publishEvent(IntraCliqueManager.BroadCastTx(txs))
      }
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
