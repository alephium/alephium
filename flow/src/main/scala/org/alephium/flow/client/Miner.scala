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

package org.alephium.flow.client

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.actor.Props
import com.typesafe.scalalogging.LazyLogging

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, ChainHandler}
import org.alephium.flow.handler.FlowHandler.BlockFlowTemplate
import org.alephium.flow.model.{BlockTemplate, DataOrigin}
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.setting.{AlephiumConfig, MiningSetting}
import org.alephium.protocol.config.{BrokerConfig, EmissionConfig, GroupConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._
import org.alephium.util.EventStream.Subscriber

object Miner extends LazyLogging {
  def props(node: Node)(implicit config: AlephiumConfig): Props =
    props(config.network.networkType, config.minerAddresses, node.blockFlow, node.allHandlers)(
      config.broker,
      config.consensus,
      config.mining
    )

  def props(
      networkType: NetworkType,
      addresses: AVector[Address],
      blockFlow: BlockFlow,
      allHandlers: AllHandlers
  )(implicit
      brokerConfig: BrokerConfig,
      emissionConfig: EmissionConfig,
      miningConfig: MiningSetting
  ): Props = {
    validateAddresses(addresses).left.foreach { error =>
      logger.error(s"Invalid miner addresses: ${addresses.toString}, due to $error")
      sys.exit(1)
    }

    Props(new Miner(networkType, addresses.map(_.lockupScript), blockFlow, allHandlers))
  }

  sealed trait Command
  case object IsMining                                              extends Command
  case object Start                                                 extends Command
  case object Stop                                                  extends Command
  case object UpdateTemplate                                        extends Command
  case object GetAddresses                                          extends Command
  final case class GetBlockCandidate(index: ChainIndex)             extends Command
  final case class Mine(index: ChainIndex, template: BlockTemplate) extends Command
  final case class NewBlockSolution(block: Block, chainIndex: ChainIndex, miningCount: U256)
      extends Command
  final case class MiningResult(blockOpt: Option[Block], chainIndex: ChainIndex, miningCount: U256)
      extends Command
  final case class UpdateAddresses(addresses: AVector[Address]) extends Command

  final case class BlockCandidate(maybeBlock: Option[BlockTemplate])

  def mine(index: ChainIndex, template: BlockTemplate)(implicit
      groupConfig: GroupConfig,
      miningConfig: MiningSetting
  ): Option[(Block, U256)] = {
    val nonceStart = UnsecureRandom.nextU256NonUniform(U256.HalfMaxValue)
    val nonceEnd   = nonceStart.addUnsafe(miningConfig.nonceStep)

    @tailrec
    def iter(current: U256): Option[(Block, U256)] = {
      if (current < nonceEnd) {
        val header = template.buildHeader(current)
        if (PoW.checkMined(header, index)) {
          val numTry = current.subUnsafe(nonceStart).addOneUnsafe()
          Some((Block(header, template.transactions), numTry))
        } else {
          iter(current.addOneUnsafe())
        }
      } else {
        None
      }
    }
    iter(nonceStart)
  }

  def nextTimeStamp(template: BlockFlowTemplate): TimeStamp = {
    nextTimeStamp(template.templateTs)
  }

  def nextTimeStamp(templateTs: TimeStamp): TimeStamp = {
    val resultTs = TimeStamp.now()
    if (resultTs <= templateTs) {
      templateTs.plusMillisUnsafe(1)
    } else {
      resultTs
    }
  }

  def validateAddresses(
      addresses: AVector[Address]
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
}

class Miner(
    networkType: NetworkType,
    var addresses: AVector[LockupScript],
    blockFlow: BlockFlow,
    allHandlers: AllHandlers
)(implicit
    val brokerConfig: BrokerConfig,
    val emissionConfig: EmissionConfig,
    val miningConfig: MiningSetting
) extends BaseActor
    with MinerState
    with Subscriber {
  val handlers = allHandlers

  def receive: Receive = handleAddresses orElse handleMining()

  var miningStarted: Boolean = false

  // scalastyle:off method.length
  def handleMining(): Receive = {
    case Miner.Start =>
      if (!miningStarted) {
        log.info("Start mining")
        subscribeEvent(self, classOf[ChainHandler.FlowDataAdded])
        updateTasks()
        startNewTasks()
        miningStarted = true
      } else {
        log.info("Mining already started")
      }
    case Miner.Stop =>
      if (miningStarted) {
        log.info("Stop mining")
        unsubscribeEvent(self, classOf[ChainHandler.FlowDataAdded])
        postMinerStop()
        miningStarted = false
      } else {
        log.info("Mining already stopped")
      }
    case Miner.Mine(index, template) => mine(index, template)
    case Miner.NewBlockSolution(block, index, miningCount) =>
      log.debug(s"Send the new mined block ${block.hash.shortHex} to blockHandler")
      val handlerMessage = BlockChainHandler.Validate(block, ActorRefT(self), Local)
      allHandlers.getBlockHandler(index) ! handlerMessage
      self ! Miner.MiningResult(Some(block), index, miningCount)
    case Miner.MiningResult(blockOpt, chainIndex, miningCount) =>
      handleMiningResult(blockOpt, chainIndex, miningCount)
    case ChainHandler.FlowDataAdded(_, origin) =>
      origin match {
        case DataOrigin.Local => () // we have updated the tasks when receiving BlockAdded
        case _: DataOrigin.FromClique =>
          updateTasks()
      }
    case BlockChainHandler.BlockAdded(hash) =>
      continueWorkFor(ChainIndex.from(hash))
    case BlockChainHandler.InvalidBlock(hash) =>
      log.error(s"Mined an invalid block ${hash.shortHex}")
      continueWorkFor(ChainIndex.from(hash))
    case Miner.IsMining => sender() ! miningStarted
  }
  // scalastyle:on method.length

  def continueWorkFor(chainIndex: ChainIndex): Unit = {
    updateTasks()
    if (miningStarted) {
      setIdle(chainIndex)
      startNewTasks()
    }
  }

  def handleMiningResult(
      blockOpt: Option[Block],
      chainIndex: ChainIndex,
      miningCount: U256
  ): Unit = {
    assume(brokerConfig.contains(chainIndex.from))
    val fromShift = chainIndex.from.value - brokerConfig.groupFrom
    val to        = chainIndex.to.value
    increaseCounts(fromShift, to, miningCount)
    blockOpt match {
      case Some(block) =>
        val miningCount = getMiningCount(fromShift, to)
        val txCount     = block.transactions.length
        log.debug(s"MiningCounts: $countsToString")
        val minerAddress =
          Address(networkType, block.coinbase.unsigned.fixedOutputs.head.lockupScript).toBase58
        log.info(
          s"A new block ${block.shortHex} got mined for $chainIndex, tx: $txCount, " +
            s"miningCount: $miningCount, target: ${block.header.target}, miner: $minerAddress"
        )
      case None =>
        if (miningStarted) {
          setIdle(fromShift, to)
          startNewTasks()
        }
    }
  }

  def handleAddresses: Receive = {
    case Miner.UpdateAddresses(newAddresses) => {
      Miner.validateAddresses(newAddresses) match {
        case Right(_) =>
          addresses = newAddresses.map(_.lockupScript)
          updateTasks()
        case Left(error) =>
          log.debug(s"Invalid new miner addresses: $newAddresses, due to $error")
      }
    }
    case Miner.GetAddresses             => sender() ! addresses
    case Miner.GetBlockCandidate(index) => sender() ! Miner.BlockCandidate(pickChainTasks(index))
  }

  private def coinbase(
      chainIndex: ChainIndex,
      txs: AVector[Transaction],
      to: Int,
      target: Target,
      blockTs: TimeStamp
  ): Transaction = {
    Transaction.coinbase(chainIndex, txs, addresses(to), target, blockTs)
  }

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
    assume(
      0 <= fromShift && fromShift < brokerConfig.groupNumPerBroker && 0 <= to && to < brokerConfig.groups
    )
    val index        = ChainIndex.unsafe(brokerConfig.groupFrom + fromShift, to)
    val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
    val blockTs      = Miner.nextTimeStamp(flowTemplate)
    val coinbaseTx   = coinbase(index, flowTemplate.transactions, to, flowTemplate.target, blockTs)
    BlockTemplate(
      flowTemplate.deps,
      flowTemplate.depStateHash,
      flowTemplate.target,
      blockTs,
      flowTemplate.transactions :+ coinbaseTx
    )
  }

  def startTask(
      fromShift: Int,
      to: Int,
      template: BlockTemplate,
      blockHandler: ActorRefT[BlockChainHandler.Command]
  ): Unit = {
    val index = ChainIndex.unsafe(fromShift + brokerConfig.groupFrom, to)
    scheduleOnce(self, Miner.Mine(index, template), miningConfig.batchDelay)
  }

  def mine(index: ChainIndex, template: BlockTemplate): Unit = {
    val task = Future {
      Miner.mine(index, template) match {
        case Some((block, miningCount)) =>
          self ! Miner.NewBlockSolution(block, index, miningCount)
        case None =>
          self ! Miner.MiningResult(None, index, miningConfig.nonceStep)
      }
    }(context.dispatcher)
    task.onComplete {
      case Success(_) => ()
      case Failure(e) => log.debug("Mining task failed", e)
    }(context.dispatcher)
  }
}
