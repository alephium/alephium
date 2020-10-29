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

package org.alephium.protocol.model

import scala.annotation.tailrec

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp, U256}

final case class Block(header: BlockHeader, transactions: AVector[Transaction])
    extends HashSerde[Block]
    with FlowData {
  override def hash: Hash = header.hash

  def coinbase: Transaction = transactions.last

  def coinbaseReward: U256 = coinbase.unsigned.fixedOutputs.head.amount

  def nonCoinbase: AVector[Transaction] = transactions.init

  override def timestamp: TimeStamp = header.timestamp

  override def target: Target = header.target

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    header.chainIndex
  }

  def isGenesis: Boolean = header.isGenesis

  def parentHash(implicit config: GroupConfig): Hash = {
    header.parentHash
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Hash = {
    header.uncleHash(toIndex)
  }

  // we shuffle non-coinbase txs randomly for execution to mitigate front-running
  def getExecutionOrder(implicit config: GroupConfig): AVector[Int] = {
    if (isGenesis) {
      AVector.tabulate(transactions.length)(identity)
    } else {
      val nonCoinbaseLength = transactions.length - 1
      val orders            = Array.tabulate(transactions.length)(identity)

      @tailrec
      def iter(index: Int, seed: Hash): Unit = {
        if (index < nonCoinbaseLength - 1) {
          val txRemaining = nonCoinbaseLength - index - 1
          val randomIndex = index + Math.floorMod(seed.toRandomIntUnsafe, txRemaining)
          val tmp         = orders(index)
          orders(index)       = orders(randomIndex)
          orders(randomIndex) = tmp
          iter(index + 1, transactions(randomIndex).hash)
        }
      }

      val initialSeed = {
        val seed0 = transactions.fold(parentHash) {
          case (acc, tx) => Hash.xor(acc, tx.hash)
        }
        transactions.fold(seed0) {
          case (acc, tx) => Hash.xor(Hash.addPerByte(acc, tx.hash), tx.hash)
        }
      }
      iter(0, initialSeed)
      AVector.unsafe(orders)
    }
  }

  def getNonCoinbaseExecutionOrder(implicit config: GroupConfig): AVector[Int] = {
    getExecutionOrder.init
  }
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.header, b.transactions))

  def from(blockDeps: AVector[Hash],
           transactions: AVector[Transaction],
           target: Target,
           nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Hash.hash(transactions)
    val timestamp   = TimeStamp.now()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(transactions: AVector[Transaction], target: Target, nonce: BigInt): Block = {
    val txsHash     = Hash.hash(transactions)
    val blockHeader = BlockHeader.genesis(txsHash, target, nonce)
    Block(blockHeader, transactions)
  }
}
