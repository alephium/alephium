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

package org.alephium.flow.mempool

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.flow.validation._
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{ChainIndex, GroupIndex, TransactionId, TransactionTemplate}
import org.alephium.util.{AVector, OptionF, TimeStamp}

class GrandPool(val mempools: AVector[MemPool], val orphanPool: OrphanPool)(implicit
    val brokerConfig: BrokerConfig
) {
  def size: Int = mempools.fold(0)(_ + _.size)

  @inline def getMemPool(mainGroup: GroupIndex): MemPool = {
    mempools(brokerConfig.groupIndexOfBroker(mainGroup))
  }

  def get(txId: TransactionId): Option[TransactionTemplate] = {
    OptionF.getAny(mempools.toIterable)(_.get(txId))
  }

  def add(
      index: ChainIndex,
      transactions: AVector[TransactionTemplate],
      timeStamp: TimeStamp
  ): Int = {
    transactions.sumBy(add(index, _, timeStamp).addedCount)
  }

  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  def add(
      index: ChainIndex,
      tx: TransactionTemplate,
      timestamp: TimeStamp
  ): MemPool.NewTxCategory = {
    val result = getMemPool(index.from).add(index, tx, timestamp)
    if (index.isIntraGroup) {
      result
    } else {
      if (!result.isInstanceOf[MemPool.AddTxFailed] && brokerConfig.contains(index.to)) {
        getMemPool(index.to).addXGroupTx(index, tx, timestamp)
      }
      result
    }
  }

  def validateAndAddTx(
      blockFlow: BlockFlow,
      txValidation: TxValidation,
      tx: TransactionTemplate,
      cacheOrphanTx: Boolean
  ): Either[TxValidationError, MemPool.NewTxCategory] = {
    val chainIndex = tx.chainIndex
    assume(!brokerConfig.isIncomingChain(chainIndex))
    val mempool = getMemPool(chainIndex.from)
    if (mempool.contains(tx)) {
      Right(MemPool.AlreadyExisted)
    } else if (mempool.isDoubleSpending(chainIndex, tx)) {
      Right(MemPool.DoubleSpending)
    } else {
      txValidation.validateMempoolTxTemplate(tx, blockFlow) match {
        case Left(Right(NonExistInput)) if cacheOrphanTx =>
          orphanPool.add(tx, TimeStamp.now()) match {
            case MemPool.AddedToMemPool => Right(MemPool.AddedToOrphanPool)
            case result                 => Right(result)
          }
        case Right(_)    => Right(add(chainIndex, tx, TimeStamp.now()))
        case Left(error) => Left(error)
      }
    }
  }

  def getOutTxsWithTimestamp(): AVector[(TimeStamp, TransactionTemplate)] = {
    mempools.flatMap(_.getOutTxsWithTimestamp())
  }

  def cleanInvalidTxs(
      blockFlow: BlockFlow,
      timeStampThreshold: TimeStamp
  ): Int = {
    mempools.fold(0)(_ + _.cleanInvalidTxs(blockFlow, timeStampThreshold))
  }

  def cleanMemPool(blockFlow: BlockFlow, now: TimeStamp)(implicit
      memPoolSetting: MemPoolSetting
  ): Unit = {
    val unconfirmedTxThreshold = now.minusUnsafe(memPoolSetting.unconfirmedTxExpiryDuration)
    mempools.foreach(_.cleanUnconfirmedTxs(unconfirmedTxThreshold))
    cleanInvalidTxs(blockFlow, now.minusUnsafe(memPoolSetting.cleanMempoolFrequency))
    ()
  }

  def clear(): Unit = {
    mempools.foreach(_.clear())
    orphanPool.clear()
  }

  def validateAllTxs(blockFlow: BlockFlow): Int = {
    cleanInvalidTxs(blockFlow, TimeStamp.now())
  }
}

object GrandPool {
  def empty(implicit brokerConfig: BrokerConfig, memPoolSetting: MemPoolSetting): GrandPool = {
    val mempools = AVector.tabulate(brokerConfig.groupNumPerBroker) { idx =>
      val group = GroupIndex.unsafe(brokerConfig.groupRange(idx))
      MemPool.empty(group)
    }
    new GrandPool(mempools, OrphanPool.default())
  }
}
