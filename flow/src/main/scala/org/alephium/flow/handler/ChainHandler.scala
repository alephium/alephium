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
import io.prometheus.client.{Gauge, Counter, Histogram}
import org.alephium.flow.core.BlockHeaderChain

object ChainHandler {
  trait Event

  final case class FlowDataAdded(data: FlowData, origin: DataOrigin, addedAt: TimeStamp)
      extends Event
      with EventStream.Event

  val chainValidationFailed: Counter = Counter
    .build(
      "alephium_chain_validation_failed",
      "Error count of chain validation errors"
    )
    .labelNames("validation_type", "invalid_status")
    .register()

  val chainValidationTotal: Counter = Counter
    .build(
      "alephium_chain_validation_total",
      "Total number of chain validations"
    )
    .labelNames("validation_type")
    .register()

  val chainValidationOngoing: Gauge = Gauge
    .build(
      "alephium_chain_validation_current",
      "Current count of chain validations"
    )
    .labelNames("validation_type")
    .register()

  val chainValidationDurationMilliSeconds: Histogram = Histogram
    .build(
      "alephium_chain_validation_duration_milliseconds",
      "Duration of the validation"
    )
    .labelNames("validation_type")
    .buckets(0.5, 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000, 300000,
      600000, 1800000, 3600000)
    .register()

  val blockDurationMilliSeconds: Histogram = Histogram
    .build(
      "alephium_block_duration_milliseconds",
      "Block duration"
    )
    .labelNames("chain_from", "chain_to")
    .buckets(0.5, 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000, 300000,
      600000, 1800000, 3600000)
    .register()
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

    ChainHandler.chainValidationOngoing.labels(data.`type`).inc()
    ChainHandler.chainValidationTotal.labels(data.`type`).inc()

    val startTime           = System.nanoTime()
    val validationResult    = validator.validate(data, blockFlow)
    val elapsedMilliSeconds = (System.nanoTime() - startTime) / 1000000d

    ChainHandler.chainValidationDurationMilliSeconds
      .labels(data.`type`)
      .observe(elapsedMilliSeconds)
    ChainHandler.chainValidationOngoing.labels(data.`type`).dec()

    validationResult match {
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
    ChainHandler.chainValidationFailed.labels(data.`type`, "IOError").inc()

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
    ChainHandler.chainValidationFailed.labels(data.`type`, status.name).inc()

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
            publishEvent(ChainHandler.FlowDataAdded(data, origin, TimeStamp.now()))
            notifyBroker(broker, data)
            log.info(show(data))
            measure(data)
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

  def measure(data: T): Unit

  def showHeader(header: BlockHeader): String = {
    val total = blockFlow.numHashes
    val index = header.chainIndex
    val chain = blockFlow.getHeaderChain(header)
    val targetRatio =
      (BigDecimal(header.target.value) / BigDecimal(consensusConfig.maxMiningTarget.value)).toFloat
    val blockTime = getBlockTime(header, chain).map(time => s"${time}ms").getOrElse("?ms")

    s"hash: ${header.shortHex}; $index; ${chain.showHeight(header.hash)}; total: $total; targetRatio: $targetRatio, blockTime: $blockTime"
  }

  protected def measureBlockTime(header: BlockHeader): BlockHeaderChain = {
    val chain      = blockFlow.getHeaderChain(header)
    val (from, to) = getChainIndexLabels(header)

    getBlockTime(header, chain).foreach { blockTime =>
      ChainHandler.blockDurationMilliSeconds.labels(from, to).observe(blockTime.toDouble)
    }

    chain
  }

  protected def getChainIndexLabels(header: BlockHeader): (String, String) = {
    val index = header.chainIndex
    (index.from.value.toString, index.to.value.toString)
  }

  private def getBlockTime(header: BlockHeader, chain: BlockHeaderChain): Option[Long] = {
    chain
      .getBlockHeader(header.parentHash)
      .toOption
      .map(header.timestamp.millis - _.timestamp.millis)
  }
}
