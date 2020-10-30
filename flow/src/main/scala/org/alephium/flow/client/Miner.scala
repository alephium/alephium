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
import scala.util.{Failure, Random, Success}

import akka.actor.Props

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.{AllHandlers, BlockChainHandler, FlowHandler}
import org.alephium.flow.model.BlockTemplate
import org.alephium.flow.model.DataOrigin.Local
import org.alephium.flow.setting.MiningSetting
import org.alephium.flow.validation.Validation
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{ActorRefT, AVector, BaseActor}

object Miner {
  def props(node: Node)(implicit brokerConfig: BrokerConfig, miningSetting: MiningSetting): Props =
    props(node.blockFlow, node.allHandlers)

  def props(blockFlow: BlockFlow, allHandlers: AllHandlers)(implicit brokerConfig: BrokerConfig,
                                                            miningSetting: MiningSetting): Props = {
    val addresses = AVector.tabulate(brokerConfig.groups) { i =>
      val index          = GroupIndex.unsafe(i)
      val (_, publicKey) = index.generateKey
      publicKey
    }
    props(addresses, blockFlow, allHandlers)
  }

  def props(addresses: AVector[PublicKey], blockFlow: BlockFlow, allHandlers: AllHandlers)(
      implicit brokerConfig: BrokerConfig,
      miningConfig: MiningSetting): Props = {
    require(addresses.length == brokerConfig.groups)
    addresses.foreachWithIndex { (publicKey, i) =>
      val lockupScript = LockupScript.p2pkh(publicKey)
      require(lockupScript.groupIndex.value == i)
    }
    Props(new Miner(addresses, blockFlow, allHandlers))
  }

  sealed trait Command
  case object Start          extends Command
  case object Stop           extends Command
  case object UpdateTemplate extends Command
  final case class MiningResult(blockOpt: Option[Block],
                                chainIndex: ChainIndex,
                                miningCount: BigInt)
      extends Command

  def mine(index: ChainIndex, template: BlockTemplate)(
      implicit groupConfig: GroupConfig,
      miningConfig: MiningSetting): Option[(Block, BigInt)] = {
    val nonceStart = BigInt(Random.nextInt(Integer.MAX_VALUE))
    val nonceEnd   = nonceStart + miningConfig.nonceStep

    @tailrec
    def iter(current: BigInt): Option[(Block, BigInt)] = {
      if (current < nonceEnd) {
        val header = template.buildHeader(current)
        if (Validation.validateMined(header, index)) {
          Some((Block(header, template.transactions), current - nonceStart + 1))
        } else {
          iter(current + 1)
        }
      } else {
        None
      }
    }
    iter(nonceStart)
  }
}

class Miner(addresses: AVector[PublicKey], blockFlow: BlockFlow, allHandlers: AllHandlers)(
    implicit val brokerConfig: BrokerConfig,
    val miningConfig: MiningSetting)
    extends BaseActor
    with MinerState {
  val handlers = allHandlers

  def receive: Receive = awaitStart

  def awaitStart: Receive = {
    case Miner.Start =>
      log.info("Start mining")
      handlers.flowHandler ! FlowHandler.Register(ActorRefT[Miner.Command](self))
      updateTasks()
      startNewTasks()
      context become (handleMining orElse awaitStop)
    case event =>
      log.debug(s"ignore miner event: $event")
  }

  def awaitStop: Receive = {
    case Miner.Stop =>
      log.info("Stop mining")
      handlers.flowHandler ! FlowHandler.UnRegister
      postMinerStop()
      context become awaitStart
    case cmd: Miner.Command =>
      log.debug(s"ignore miner commands: $cmd")
  }

  def handleMining: Receive = {
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
          log.info(
            s"A new block ${block.shortHex} got mined for $chainIndex, tx: $txCount, miningCount: $miningCount, target: ${block.header.target}")
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

  private def coinbase(txs: AVector[Transaction], to: Int, height: Int): Transaction = {
    val minerMessage = Hash.generate.bytes
    Transaction.coinbase(txs, addresses(to), height, minerMessage)
  }

  def prepareTemplate(fromShift: Int, to: Int): BlockTemplate = {
    assume(
      0 <= fromShift && fromShift < brokerConfig.groupNumPerBroker && 0 <= to && to < brokerConfig.groups)
    val index        = ChainIndex.unsafe(brokerConfig.groupFrom + fromShift, to)
    val flowTemplate = blockFlow.prepareBlockFlowUnsafe(index)
    BlockTemplate(
      flowTemplate.deps,
      flowTemplate.target,
      flowTemplate.transactions :+ coinbase(flowTemplate.transactions, to, flowTemplate.height))
  }

  def startTask(fromShift: Int,
                to: Int,
                template: BlockTemplate,
                blockHandler: ActorRefT[BlockChainHandler.Command]): Unit = {
    val task = Future {
      val index = ChainIndex.unsafe(fromShift + brokerConfig.groupFrom, to)
      Miner.mine(index, template) match {
        case Some((block, miningCount)) =>
          log.debug(s"Send the new mined block ${block.hash.shortHex} to blockHandler")
          val handlerMessage = BlockChainHandler.addOneBlock(block, Local)
          blockHandler ! handlerMessage
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
