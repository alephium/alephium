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

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, ChainIndex, FlowData}
import org.alephium.serde.{serialize, Serde}
import org.alephium.util._
import org.alephium.util.EventStream.Publisher

object ChainHandler {
  trait Event

  final case class FlowDataAdded(data: FlowData, origin: DataOrigin)
      extends Event
      with EventStream.Event
}

abstract class ChainHandler[T <: FlowData: Serde, S <: InvalidStatus, Command](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    validator: Validation[T, S]
) extends IOBaseActor
    with Publisher {
  import ChainHandler.Event

  def consensusConfig: ConsensusConfig

  def handleData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.debug(s"Try to add ${data.shortHex}")
    validator.validate(data, blockFlow) match {
      case Left(Left(e))                 => handleIOError(data, broker, e)
      case Left(Right(x: InvalidStatus)) => handleInvalidData(data, broker, origin, x)
      case Right(_)                      => handleValidData(data, broker, origin)
    }
  }

  def handleIOError(
      data: T,
      broker: ActorRefT[ChainHandler.Event],
      error: IOError
  ): Unit = {
    val blockHex = Hex.toHexString(serialize(data))
    log.error(
      s"IO failed in block/header ${data.hash.shortHex}: $blockHex validation: $error"
    )
    broker ! dataAddingFailed()
  }

  def handleInvalidData(
      data: T,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin,
      status: InvalidStatus
  ): Unit = {
    val blockHex = Hex.toHexString(serialize(data))
    log.warning(s"Invalid block/header ${data.shortHex}: $status : $blockHex")
    if (!origin.isLocal) {
      sender() ! DependencyHandler.Invalid(data.hash)
    }
    broker ! dataInvalid(data)
  }

  def handleValidData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.info(s"${data.shortHex} is validated")
    if (blockFlow.isRecent(data)) {
      broadcast(data, origin)
    }
    blockFlow.contains(data.hash) match {
      case Right(true) =>
        log.debug(s"Block/Header ${data.shortHex} exists already")
      case Right(false) =>
        addDataToBlockFlow(data) match {
          case Left(error) => handleIOError(error)
          case Right(_) =>
            publishEvent(ChainHandler.FlowDataAdded(data, origin))
            notifyBroker(broker, data)
            log.info(show(data))
        }
      case Left(error) => handleIOError(error)
    }
  }

  def addDataToBlockFlow(data: T): IOResult[Unit]

  def broadcast(data: T, origin: DataOrigin): Unit

  def notifyBroker(broker: ActorRefT[ChainHandler.Event], data: T): Unit

  def dataAddingFailed(): Event

  def dataInvalid(data: T): Event

  def show(data: T): String

  def showHeader(header: BlockHeader): String = {
    val total = blockFlow.numHashes
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val targetRatio =
      (BigDecimal(header.target.value) / BigDecimal(consensusConfig.maxMiningTarget.value)).toFloat
    val blockTime = {
      chain.getBlockHeader(header.parentHash) match {
        case Left(_) => "?ms"
        case Right(parentHeader) =>
          val span = header.timestamp.millis - parentHeader.timestamp.millis
          s"${span}ms"
      }
    }
    s"hash: ${header.shortHex}; $index; ${chain.showHeight(header.hash)}; total: $total; targetRatio: $targetRatio, blockTime: $blockTime"
  }
}
