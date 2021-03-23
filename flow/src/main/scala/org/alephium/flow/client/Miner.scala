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

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, FlowHandler}
import org.alephium.flow.handler.FlowHandler.BlockFlowTemplate
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.setting.{AlephiumConfig, MiningSetting}
import org.alephium.protocol.config.{BrokerConfig, EmissionConfig, GroupConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

object Miner {
  def props(node: Node)(implicit config: AlephiumConfig): Props =
    props(config.network.networkType, config.minerAddresses, node.blockFlow, node.allHandlers)(
      config.broker,
      config.consensus,
      config.mining
    )

  def props(
      networkType: NetworkType,
      addresses: AVector[LockupScript],
      blockFlow: BlockFlow,
      allHandlers: AllHandlers
  )(implicit
      brokerConfig: BrokerConfig,
      emissionConfig: EmissionConfig,
      miningConfig: MiningSetting
  ): Props = {
    require(validateAddresses(addresses))
    Props(new Miner(networkType, addresses, blockFlow, allHandlers))
  }

  sealed trait Command
  case object Start                                                 extends Command
  case object Stop                                                  extends Command
  case object UpdateTemplate                                        extends Command
  case object GetAddresses                                          extends Command
  final case class Mine(index: ChainIndex, template: BlockTemplate) extends Command
  final case class MiningResult(blockOpt: Option[Block], chainIndex: ChainIndex, miningCount: U256)
      extends Command
  final case class UpdateAddresses(addresses: AVector[LockupScript]) extends Command

  def mine(index: ChainIndex, template: BlockTemplate)(implicit
      groupConfig: GroupConfig,
      miningConfig: MiningSetting
  ): Option[(Block, U256)] = {
    val nonceStart = Random.nextU256NonUniform(U256.HalfMaxValue)
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
    nextTimeStamp(template.parentTs)
  }

  def nextTimeStamp(parentTs: TimeStamp): TimeStamp = {
    val resultTs = TimeStamp.now()
    if (resultTs <= parentTs) {
      parentTs.plusMillisUnsafe(1)
    } else {
      resultTs
    }
  }

  def validateAddresses(
      addresses: AVector[LockupScript]
  )(implicit groupConfig: GroupConfig): Boolean = {
    (addresses.length == groupConfig.groups) &&
    addresses.forallWithIndex { (lockupScript, i) => lockupScript.groupIndex.value == i }
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
    with MinerState {
  val handlers = allHandlers

  def receive: Receive = handleAddresses orElse awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      handlers.flowHandler ! FlowHandler.Register(ActorRefT[Miner.Command](self))
      updateTasks()
      startNewTasks()
      context become (handleMining orElse handleAddresses orElse awaitStop)
    case event =>
      log.debug(s"ignore miner event: $event")
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      handlers.flowHandler ! FlowHandler.UnRegister
      postMinerStop()
      context become (handleAddresses orElse awaitStart)
    case cmd: Miner.Command =>
      log.debug(s"ignore miner commands: $cmd")
  }

  def handleMining: Receive = {
    case Miner.Mine(index, template) => mine(index, template)
    case Miner.MiningResult(blockOpt, chainIndex, miningCount) =>
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
          setIdle(fromShift, to)
          startNewTasks()
      }
    case Miner.UpdateTemplate =>
      updateTasks()
    case BlockChainHandler.BlockAdded(hash) =>
      val chainIndex = ChainIndex.from(hash)
      val fromShift  = chainIndex.from.value - brokerConfig.groupFrom
      val to         = chainIndex.to.value
      updateTasks()
      setIdle(fromShift, to)
      startNewTasks()
  }

  def handleAddresses: Receive = {
    case Miner.UpdateAddresses(newAddresses) => {
      if (Miner.validateAddresses(newAddresses)) {
        addresses = newAddresses
        updateTasks()
      } else {
        log.debug(s"Invalid new miner addresses: $newAddresses")
      }
    }
    case Miner.GetAddresses => sender() ! addresses
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
          log.debug(s"Send the new mined block ${block.hash.shortHex} to blockHandler")
          val handlerMessage = BlockChainHandler.Validate(block, ActorRefT(self), Local)
          allHandlers.getBlockHandler(index) ! handlerMessage
          self ! Miner.MiningResult(Some(block), index, miningCount)
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
