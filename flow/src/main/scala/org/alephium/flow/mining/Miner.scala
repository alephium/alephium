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

package org.alephium.flow.mining

import scala.annotation.tailrec
import scala.util.{Failure, Success}

import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging

import org.alephium.flow.Utils
import org.alephium.flow.model.BlockFlowTemplate
import org.alephium.flow.setting.MiningSetting
import org.alephium.flow.validation.InvalidTestnetMiner
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.serde.deserialize
import org.alephium.util._

object Miner extends LazyLogging {
  sealed trait Command
  case object IsMining                                                      extends Command
  case object Start                                                         extends Command
  case object Stop                                                          extends Command
  final case class Mine(index: ChainIndex, job: Job)                        extends Command
  final case class NewBlockSolution(block: Block, miningCount: U256)        extends Command
  final case class MiningNoBlock(chainIndex: ChainIndex, miningCount: U256) extends Command

  def mine(index: ChainIndex, job: Job)(implicit
      groupConfig: GroupConfig,
      miningConfig: MiningSetting
  ): Option[(Block, U256)] = {
    mine(index, job.headerBlob, Target.unsafe(job.target)).map { case (nonce, miningCount) =>
      val blockBlob = job.toBlockBlob(nonce)
      deserialize[Block](blockBlob) match {
        case Left(error)  => throw new RuntimeException(s"Unable to deserialize block: $error")
        case Right(block) => block -> miningCount
      }
    }
  }

  def mineForDev(index: ChainIndex, template: BlockFlowTemplate)(implicit
      groupConfig: GroupConfig
  ): Block = {
    val job       = Job.from(template)
    val target    = template.target
    val nonceStep = U256.unsafe(Int.MaxValue)
    mine(index, job.headerBlob, target, nonceStep) match {
      case Some((nonce, _)) =>
        val blockBlob = job.toBlockBlob(nonce)
        deserialize[Block](blockBlob) match {
          case Left(error)  => throw new RuntimeException(s"Unable to deserialize block: $error")
          case Right(block) => block
        }
      case None => throw new RuntimeException(s"Difficulty is set too high for dev")
    }
  }

  def mine(index: ChainIndex, headerBlob: ByteString, target: Target)(implicit
      groupConfig: GroupConfig,
      miningConfig: MiningSetting
  ): Option[(Nonce, U256)] = {
    mine(index, headerBlob, target, miningConfig.nonceStep)
  }

  def mine(index: ChainIndex, headerBlob: ByteString, target: Target, nonceStep: U256)(implicit
      groupConfig: GroupConfig
  ): Option[(Nonce, U256)] = {
    val noncePostfixArray = Array.ofDim[Byte](Nonce.byteLength - 4)
    UnsecureRandom.source.nextBytes(noncePostfixArray)
    val noncePostfix   = ByteString.fromArrayUnsafe(noncePostfixArray)
    var noncePrefixInt = UnsecureRandom.source.nextInt()

    @tailrec
    def iter(toTry: U256): Option[(Nonce, U256)] = {
      if (toTry != U256.Zero) {
        noncePrefixInt += 1
        val noncePrefix   = Bytes.from(noncePrefixInt)
        val rawNonce      = noncePrefix ++ noncePostfix
        val newHeaderBlob = rawNonce ++ headerBlob
        if (PoW.checkMined(index, newHeaderBlob, target)) {
          Some(Nonce.unsafe(rawNonce) -> (nonceStep subUnsafe toTry))
        } else {
          iter(toTry.subUnsafe(U256.One))
        }
      } else {
        None
      }
    }

    iter(nonceStep.subUnsafe(U256.One))
  }

  def validateAddresses(
      addresses: AVector[Address.Asset]
  )(implicit groupConfig: GroupConfig): Either[String, Unit] = {
    if (addresses.length != groupConfig.groups) {
      Left(s"Wrong number of addresses, expected ${groupConfig.groups}, got ${addresses.length}")
    } else {
      addresses
        .foreachWithIndexE { (address, i) =>
          if (address.lockupScript.groupIndex.value == i) {
            Right(())
          } else {
            Left(s"Address ${address.toBase58} doesn't belong to group $i")
          }
        }
    }
  }

  @inline def validateTestnetMiners(
      minersOpt: Option[AVector[Address.Asset]]
  )(implicit network: NetworkConfig): Either[String, Unit] = {
    minersOpt match {
      case Some(miners) => validateTestnetMiners(miners)
      case None         => Right(())
    }
  }

  @inline def validateTestnetMiners(
      miners: AVector[Address.Asset]
  )(implicit network: NetworkConfig): Either[String, Unit] = {
    if (network.networkId == NetworkId.AlephiumTestNet) {
      if (ALPH.isTestnetMinersWhitelisted(miners)) {
        Right(())
      } else {
        Left(InvalidTestnetMiner.toString)
      }
    } else {
      Right(())
    }
  }
}

trait Miner extends Utils.BaseActorWithPoolExecutor with MinerState {
  @volatile var miningStarted: Boolean = false

  // scalastyle:off method.length
  def handleMining: Receive = {
    case Miner.Start =>
      if (!miningStarted) {
        log.info("Start mining")
        subscribeForTasks()
        miningStarted = true
      } else {
        log.info("Mining already started")
      }
    case Miner.Stop =>
      if (miningStarted) {
        log.info("Stop mining")
        unsubscribeTasks()
        postMinerStop()
        miningStarted = false
      } else {
        log.info("Mining already stopped")
      }
    case Miner.Mine(index, job) => mine(index, job)
    case Miner.NewBlockSolution(block, miningCount) =>
      log.debug(s"Publish the new mined block ${block.hash.shortHex}")
      publishNewBlock(block)
      handleNewBlock(block, miningCount)
    case Miner.MiningNoBlock(chainIndex, miningCount) =>
      handleNoBlock(chainIndex, miningCount)
    case Miner.IsMining => sender() ! miningStarted
  }
  // scalastyle:on method.length

  def handleMiningTasks: Receive

  def subscribeForTasks(): Unit

  def unsubscribeTasks(): Unit

  def publishNewBlock(block: Block): Unit

  def handleNewBlock(block: Block, miningCount: U256): Unit = {
    val chainIndex = block.chainIndex
    assume(brokerConfig.contains(chainIndex.from))
    val fromShift = brokerConfig.groupIndexOfBroker(chainIndex.from)
    val to        = chainIndex.to.value
    increaseCounts(fromShift, to, miningCount)
    pendingTasks(fromShift)(to) = None

    val totalCount = getMiningCount(fromShift, to)
    val txCount    = block.transactions.length
    log.debug(s"MiningCounts: $countsToString")
    log.info(
      s"A new block ${block.hash.toHexString} got mined for $chainIndex, tx: $txCount, " +
        s"miningCount: $totalCount, target: ${block.header.target}"
    )
  }

  def handleNoBlock(chainIndex: ChainIndex, miningCount: U256): Unit = {
    val fromShift = brokerConfig.groupIndexOfBroker(chainIndex.from)
    val to        = chainIndex.to.value
    increaseCounts(fromShift, to, miningCount)
    if (miningStarted) {
      setIdle(fromShift, to)
      startNewTasks()
    }
  }

  def startTask(
      fromShift: Int,
      to: Int,
      job: Job
  ): Unit = {
    val index = ChainIndex.unsafe(brokerConfig.groupRange(fromShift), to)
    scheduleOnce(self, Miner.Mine(index, job), miningConfig.batchDelay)
  }

  def mine(index: ChainIndex, job: Job): Unit = {
    val task = poolAsync {
      Miner.mine(index, job) match {
        case Some((block, miningCount)) =>
          self ! Miner.NewBlockSolution(block, miningCount)
        case None =>
          self ! Miner.MiningNoBlock(index, miningConfig.nonceStep)
      }
    }
    task.onComplete {
      case Success(_) => ()
      case Failure(e) => log.debug(s"Mining task failed: ${e.getMessage}")
    }
  }
}
