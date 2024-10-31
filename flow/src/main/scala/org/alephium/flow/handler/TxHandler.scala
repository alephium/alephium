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
import org.alephium.flow.handler.AllHandlers.BlockNotify
import org.alephium.flow.io.PendingTxStorage
import org.alephium.flow.mempool.MemPool
import org.alephium.flow.mining.Miner
import org.alephium.flow.model.{DataOrigin, PersistedTxId}
import org.alephium.flow.network.{InterCliqueManager, IntraCliqueManager}
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.sync.FetchState
import org.alephium.flow.setting.{MemPoolSetting, NetworkSetting}
import org.alephium.flow.validation._
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.message.{Message, NewBlock}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, LogConfig}
import org.alephium.util._

object TxHandler {
  def props(
      blockFlow: BlockFlow,
      txStorage: PendingTxStorage,
      eventBus: ActorRefT[EventBus.Message]
  )(implicit
      brokerConfig: BrokerConfig,
      memPoolSetting: MemPoolSetting,
      networkSetting: NetworkSetting,
      logConfig: LogConfig
  ): Props = Props(new TxHandler(blockFlow, txStorage, eventBus))

  sealed trait Command
  final case class AddToMemPool(
      txs: AVector[TransactionTemplate],
      isIntraCliqueSyncing: Boolean,
      isLocalTx: Boolean
  ) extends Command
  final case class Rebroadcast(tx: TransactionTemplate) extends Command
  final case class TxAnnouncements(txs: AVector[(ChainIndex, AVector[TransactionId])])
      extends Command
  final case class MineOneBlock(chainIndex: ChainIndex) extends Command
  case object CleanMemPool                              extends Command
  case object CleanOrphanPool                           extends Command
  private[handler] case object BroadcastTxs             extends Command
  private[handler] case object DownloadTxs              extends Command
  case object ClearMemPool                              extends Command

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

  def mineTxForDev(
      blockFlow: BlockFlow,
      txTemplate: TransactionTemplate,
      publishBlock: Block => Unit
  )(implicit groupConfig: GroupConfig): Either[String, Unit] = {
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
      case error =>
        Left(s"Unable to add the tx the mempool: ${error}")
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
      block = Miner.mineForDev(chainIndex, flowTemplate)
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

final class TxHandler(
    val blockFlow: BlockFlow,
    val pendingTxStorage: PendingTxStorage,
    val eventBus: ActorRefT[EventBus.Message]
)(implicit
    val brokerConfig: BrokerConfig,
    memPoolSetting: MemPoolSetting,
    val networkSetting: NetworkSetting,
    logConfig: LogConfig
) extends TxCoreHandler
    with TxHandlerPersistence
    with BroadcastTxsHandler
    with DownloadTxsHandler
    with AutoMineHandler
    with IOBaseActor
    with EventStream.Publisher
    with InterCliqueManager.NodeSyncStatus {
  val txBufferMaxCapacity: Int = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32
  val batchBroadcastTxsFrequency: Duration = memPoolSetting.batchBroadcastTxsFrequency
  val batchDownloadTxsFrequency: Duration  = memPoolSetting.batchDownloadTxsFrequency
  val cleanOrphanTxFrequency: Duration     = memPoolSetting.cleanOrphanTxFrequency
  val orphanTxExpiryDuration: Duration     = cleanOrphanTxFrequency.timesUnsafe(2)

  val nonCoinbaseValidation = TxValidation.build
  val fetching: FetchState[TransactionId] =
    FetchState[TransactionId](
      txBufferMaxCapacity,
      networkSetting.syncExpiryPeriod,
      TxHandler.MaxDownloadTimes
    )

  override def receive: Receive = handleCommand orElse updateNodeSyncStatus

  // scalastyle:off method.length
  def handleCommand: Receive = {
    case TxHandler.AddToMemPool(txs, isIntraCliqueSyncing, isLocalTx) =>
      if (!memPoolSetting.autoMineForDev) {
        if (isIntraCliqueSyncing) {
          txs.foreach(handleIntraCliqueSyncingTx)
        } else {
          txs.foreach(handleInterCliqueTx(_, acknowledge = true, cacheOrphanTx = !isLocalTx))
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
    case TxHandler.CleanOrphanPool => cleanOrphanPool()
    case TxHandler.CleanMemPool =>
      log.debug("Start to clean mempools")
      blockFlow.getGrandPool().cleanMemPool(blockFlow, TimeStamp.now())
    case TxHandler.Rebroadcast(tx) =>
      outgoingTxBuffer.put(tx, ())
    case TxHandler.ClearMemPool =>
      blockFlow.grandPool.clear()
      clearPersistedTxs()
  }

  override def onFirstTimeSynced(): Unit = {
    clearStorageAndLoadTxs(
      handleInterCliqueTx(_, acknowledge = false, cacheOrphanTx = false)
    )
    schedule(self, TxHandler.CleanMemPool, memPoolSetting.cleanMempoolFrequency)
    scheduleOnce(self, TxHandler.CleanOrphanPool, memPoolSetting.cleanOrphanTxFrequency)
    scheduleOnce(self, TxHandler.BroadcastTxs, batchBroadcastTxsFrequency)
    scheduleOnce(self, TxHandler.DownloadTxs, batchDownloadTxsFrequency)
  }

  override def postStop(): Unit = {
    super.postStop()
    persistMempoolTxs()
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
}

trait TxCoreHandler extends TxHandlerUtils {
  def blockFlow: BlockFlow
  implicit def brokerConfig: BrokerConfig
  def outgoingTxBuffer: Cache[TransactionTemplate, Unit]

  def cleanOrphanTxFrequency: Duration
  def orphanTxExpiryDuration: Duration

  def nonCoinbaseValidation: TxValidation

  def handleInterCliqueTx(
      tx: TransactionTemplate,
      acknowledge: Boolean,
      cacheOrphanTx: Boolean
  ): Unit = {
    val grandPool = blockFlow.getGrandPool()
    grandPool.validateAndAddTx(blockFlow, nonCoinbaseValidation, tx, cacheOrphanTx) match {
      case Right(MemPool.AddedToMemPool) =>
        handleValidTx(tx)
        addSucceeded(tx, acknowledge)
      case Right(MemPool.AlreadyExisted) =>
        log.debug(s"Tx ${tx.id.toHexString} is already included")
        addSucceeded(tx, acknowledge)
      case Right(MemPool.DoubleSpending) =>
        val reason = s"tx ${tx.id.shortHex} is double spending: ${tx.hex}"
        addFailed(tx, reason, acknowledge)
      case Right(MemPool.MemPoolIsFull) =>
        val reason = s"the mempool is full when trying to add the tx ${tx.id.shortHex}: ${tx.hex}"
        addFailed(tx, reason, acknowledge)
      case Right(MemPool.AddedToOrphanPool) =>
        log.debug(s"Tx ${tx.id.toHexString} is added to orphan pool")
      case Left(error) => handleInvalidTx(tx, acknowledge, error)
    }
  }

  def cleanOrphanPool(): Unit = {
    val orphanPool = blockFlow.getGrandPool().orphanPool
    orphanPool.getRootTxs().foreach(validateOrphanTx)
    orphanPool.clean(TimeStamp.now().minusUnsafe(orphanTxExpiryDuration))
    scheduleOnce(self, TxHandler.CleanOrphanPool, cleanOrphanTxFrequency)
  }

  private def validateOrphanTx(tx: TransactionTemplate): Unit = {
    val grandPool = blockFlow.getGrandPool()
    grandPool.validateAndAddTx(blockFlow, nonCoinbaseValidation, tx, false) match {
      case Left(Right(NonExistInput)) => ()
      case Left(_) | Right(MemPool.DoubleSpending) =>
        grandPool.orphanPool.removeInvalidTx(tx)
        log.debug(s"Remove invalid orphan tx ${tx.id.toHexString}: ${tx.hex}")
      case Right(MemPool.AddedToMemPool) => handleValidTx(tx)
      case Right(_)                      => ()
    }
  }

  private def handleValidTx(tx: TransactionTemplate): Unit = {
    outgoingTxBuffer.put(tx, ())
    val orphanPool = blockFlow.getGrandPool().orphanPool
    if (orphanPool.contains(tx.id)) {
      val newRoots = orphanPool.removeValidTx(tx)
      newRoots.foreach(_.foreach(validateOrphanTx))
    }
  }

  def handleIntraCliqueSyncingTx(tx: TransactionTemplate): Unit = {
    val chainIndex = tx.chainIndex
    assume(brokerConfig.isIncomingChain(chainIndex))
    blockFlow.getMemPool(chainIndex.to).addXGroupTx(chainIndex, tx, TimeStamp.now())
  }

  protected def handleInvalidTx(
      tx: TransactionTemplate,
      acknowledge: Boolean,
      error: TxValidationError
  ): Unit = {
    error match {
      case Right(s: InvalidTxStatus) =>
        addFailed(
          tx,
          s"Failed in validating tx ${tx.id.toHexString} due to $s: ${tx.hex}",
          acknowledge
        )
      case Left(e) =>
        addFailed(
          tx,
          s"IO failed in validating tx ${tx.id.toHexString} due to $e: ${tx.hex}",
          acknowledge
        )
    }
  }
}

trait DownloadTxsHandler extends TxHandlerUtils {
  def txBufferMaxCapacity: Int
  def batchDownloadTxsFrequency: Duration

  lazy val announcements: Cache[TxHandler.Announcement, Unit] =
    Cache.fifo[TxHandler.Announcement, Unit](txBufferMaxCapacity)

  protected def downloadTxs(): Unit = {
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
      downloads.foreachEntry { case (brokerHandler, chainIndexedHashes) =>
        val txHashes = chainIndexedTxsToAVector(chainIndexedHashes)
        log.debug(s"Download tx announcements ${Utils.showChainIndexedDigest(txHashes)}")
        brokerHandler ! BrokerHandler.DownloadTxs(txHashes)
      }
      announcements.clear()
    }
    scheduleOnce(self, TxHandler.DownloadTxs, batchDownloadTxsFrequency)
  }
}

trait BroadcastTxsHandler extends TxHandlerUtils {
  implicit def brokerConfig: BrokerConfig
  def txBufferMaxCapacity: Int
  def batchBroadcastTxsFrequency: Duration

  lazy val outgoingTxBuffer: Cache[TransactionTemplate, Unit] = {
    Cache.fifo[TransactionTemplate, Unit](txBufferMaxCapacity)
  }

  protected def broadcastTxs(): Unit = {
    log.debug("Start to broadcast txs")
    val broadcasts = mutable.Map.empty[ChainIndex, mutable.ArrayBuffer[TransactionTemplate]]
    if (outgoingTxBuffer.nonEmpty) {
      outgoingTxBuffer.keys().foreach { tx =>
        updateChainIndexTxs(tx, tx.chainIndex, broadcasts)
      }
      outgoingTxBuffer.clear()
    }

    if (broadcasts.nonEmpty) {
      val txs = chainIndexedTxsToAVector(broadcasts)
      publishEvent(InterCliqueManager.BroadCastTx(txs.map(p => p._1 -> p._2.map(_.id))))
      if (brokerConfig.brokerNum > 1) {
        publishEvent(IntraCliqueManager.BroadCastTx(txs))
      }
    }
    scheduleOnce(self, TxHandler.BroadcastTxs, batchBroadcastTxsFrequency)
  }
}

trait AutoMineHandler extends TxCoreHandler {
  def blockFlow: BlockFlow
  def eventBus: ActorRefT[EventBus.Message]
  implicit def brokerConfig: BrokerConfig
  implicit def networkSetting: NetworkSetting

  def mineTxsForDev(txs: AVector[TransactionTemplate]): Unit = {
    txs.foreach { tx =>
      nonCoinbaseValidation.validateMempoolTxTemplate(tx, blockFlow) match {
        case Left(error) =>
          handleInvalidTx(tx, true, error)

        case Right(_) =>
          TxHandler.mineTxForDev(blockFlow, tx, publishBlock) match {
            case Left(error) => addFailed(tx, error, acknowledge = true)
            case Right(_)    => addSucceeded(tx, acknowledge = true)
          }
      }
    }
  }

  def publishBlock(block: Block): Unit = {
    val blockMessage = Message.serialize(NewBlock(block))
    val event        = InterCliqueManager.BroadCastBlock(block, blockMessage, DataOrigin.Local)
    publishEvent(event)

    escapeIOError(blockFlow.getHeight(block)) { height =>
      eventBus ! BlockNotify(block, height)
    }
  }
}

trait TxHandlerPersistence extends TxHandlerUtils {
  def blockFlow: BlockFlow
  implicit def brokerConfig: BrokerConfig
  def pendingTxStorage: PendingTxStorage

  def clearStorageAndLoadTxs(handlePendingTx: TransactionTemplate => Unit): Unit = {
    log.info("Start to load persisted pending txs")
    var (valid, invalid) = (0, 0)
    val toRemove         = mutable.ArrayBuffer.empty[PersistedTxId]
    escapeIOError(for {
      groupViews <- AVector
        .tabulateE(brokerConfig.groupNumPerBroker) { index =>
          val groupIndex = GroupIndex.unsafe(brokerConfig.groupRange(index))
          blockFlow.getImmutableGroupViewIncludePool(groupIndex)
        }
      _ <- pendingTxStorage.iterateE { (key, tx) =>
        val chainIndex = tx.chainIndex
        val groupIndex = chainIndex.from
        val index      = brokerConfig.groupIndexOfBroker(groupIndex)
        groupViews(index).getPreAssetOutputs(tx.unsigned.inputs).map {
          case Some(_) =>
            valid += 1
            handlePendingTx(tx)
          case None =>
            toRemove += key
            invalid += 1
        }
      }
      _ <- EitherF.foreachTry(toRemove)(pendingTxStorage.remove)
    } yield ())
    log.info(
      s"Load persisted pending txs completed, valid: #$valid, invalid: #$invalid (not precise though..)"
    )
  }

  def persistMempoolTxs(): Unit = {
    log.info("Start to persist pending txs")
    clearPersistedTxs()
    escapeIOError(
      blockFlow.getGrandPool().getOutTxsWithTimestamp().foreachE { case (timestamp, tx) =>
        pendingTxStorage.put(PersistedTxId(timestamp, tx.id), tx)
      }
    )
  }

  def clearPersistedTxs(): Unit = {
    escapeIOError(pendingTxStorage.iterateE { (txId, _) =>
      pendingTxStorage.remove(txId)
    })
  }
}

trait TxHandlerUtils extends IOBaseActor with EventStream.Publisher {
  type TxsPerChain[T] = mutable.Map[ChainIndex, mutable.ArrayBuffer[T]]

  protected def updateChainIndexTxs[T](
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

  protected def chainIndexedTxsToAVector[T: ClassTag](
      txs: TxsPerChain[T]
  ): AVector[(ChainIndex, AVector[T])] = {
    txs.foldLeft(AVector.empty[(ChainIndex, AVector[T])]) { case (acc, (chainIndex, txs)) =>
      acc :+ (chainIndex -> AVector.from(txs))
    }
  }

  @inline protected def addSucceeded(tx: TransactionTemplate, acknowledge: Boolean): Unit = {
    if (acknowledge) {
      sender() ! TxHandler.AddSucceeded(tx.id)
    }
  }

  @inline protected def addFailed(
      tx: TransactionTemplate,
      reason: => String,
      acknowledge: Boolean
  ): Unit = {
    if (acknowledge) {
      sender() ! TxHandler.AddFailed(tx.id, reason: String)
    }
  }
}

trait TxHandlerIncomingTxBuffer extends TxHandlerUtils {}
