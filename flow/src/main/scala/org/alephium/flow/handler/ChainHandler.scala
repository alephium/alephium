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

import io.prometheus.client.{Counter, Gauge, Histogram}

import org.alephium.flow.core.{BlockFlow, BlockHeaderChain}
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.mining.HashRate
import org.alephium.protocol.model.{BlockHeader, ChainIndex, FlowData}
import org.alephium.serde.{serialize, Serde}
import org.alephium.util._
import org.alephium.util.EventStream.Publisher

//scalastyle:off magic.number
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

  val chainValidationDurationMilliSeconds: Histogram = Histogram
    .build(
      "alephium_chain_validation_duration_milliseconds",
      "Duration of the validation"
    )
    .labelNames("validation_type")
    .buckets(0.5, 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000)
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
  // scalastyle:on magic.number

  val blockCurrentHeight: Gauge = Gauge
    .build(
      "alephium_block_current_height",
      "Current height of the block"
    )
    .labelNames("chain_from", "chain_to")
    .register()

  val targetHashRateHertz: Gauge = Gauge
    .build(
      "alephium_target_hash_rate_hertz",
      "Target hash rate"
    )
    .labelNames("chain_from", "chain_to")
    .register()
}

abstract class ChainHandler[T <: FlowData: Serde, S <: InvalidStatus, R, V <: Validation[T, S, R]](
    blockFlow: BlockFlow,
    val chainIndex: ChainIndex,
    val validator: V
) extends IOBaseActor
    with Publisher {
  import ChainHandler._

  implicit val brokerConfig: BrokerConfig = blockFlow.brokerConfig
  def consensusConfig: ConsensusConfig

  def chainValidationTotalLabeled: Counter.Child
  def chainValidationDurationMilliSecondsLabeled: Histogram.Child

  def handleData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.debug(s"Try to add ${data.shortHex}")

    chainValidationTotalLabeled.inc()

    val startTime           = System.nanoTime()
    val validationResult    = validateWithSideEffect(data, origin)
    val elapsedMilliSeconds = (System.nanoTime() - startTime) / 1000000d

    chainValidationDurationMilliSecondsLabeled.observe(elapsedMilliSeconds)

    validationResult match {
      case Left(Left(e))  => handleIOError(data, broker, e)
      case Left(Right(x)) => handleInvalidData(data, broker, origin, x)
      case Right(validationSideEffect) =>
        handleValidData(data, validationSideEffect, broker, origin)
    }
  }

  def validateWithSideEffect(data: T, origin: DataOrigin): ValidationResult[S, R]

  def handleIOError(
      data: T,
      broker: ActorRefT[ChainHandler.Event],
      error: IOError
  ): Unit = {
    val blockHex = Hex.toHexString(serialize(data))
    log.error(
      s"IO failed in block/header ${data.hash.toHexString}: $blockHex validation: $error"
    )
    chainValidationFailed.labels(data.`type`, "IOError").inc()

    broker ! dataAddingFailed()
  }

  def handleInvalidData(
      data: T,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin,
      status: S
  ): Unit = {
    val blockHex = Hex.toHexString(serialize(data))
    log.warning(s"Invalid block/header ${data.hash.toHexString}: $status : $blockHex")
    chainValidationFailed.labels(data.`type`, status.name).inc()

    if (!origin.isLocal) {
      sender() ! DependencyHandler.Invalid(data.hash)
    }
    broker ! dataInvalid(data, status)
  }

  def handleValidData(
      data: T,
      validationSideEffect: R,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ): Unit = {
    log.info(s"${data.shortHex} is validated")
    blockFlow.contains(data.hash) match {
      case Right(true) =>
        log.debug(s"Block/Header ${data.shortHex} exists already")
      case Right(false) =>
        addDataToBlockFlow(data, validationSideEffect) match {
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

  def addDataToBlockFlow(data: T, validationSideEffect: R): IOResult[Unit]

  def notifyBroker(broker: ActorRefT[ChainHandler.Event], data: T): Unit

  def dataAddingFailed(): Event

  def dataInvalid(data: T, reason: S): Event

  def show(data: T): String

  def measure(data: T): Unit

  def showHeader(header: BlockHeader): String = {
    val total     = blockFlow.numHashes
    val index     = header.chainIndex
    val chain     = blockFlow.getHeaderChain(header)
    val hashRate  = HashRate.from(header.target, consensusConfig.blockTargetTime)
    val blockTime = chain.getBlockTime(header).fold(_ => "?ms", time => s"${time.millis}ms")

    s"hash: ${header.hash.toHexString}; ${index.prettyString}; ${chain.showHeight(
        header.hash
      )}; total: $total; target hashrate: ${hashRate.MHs}; blockTime: $blockTime"
  }

  protected def measureCommon(header: BlockHeader): BlockHeaderChain = {
    val chain = blockFlow.getHeaderChain(header)

    blockCurrentHeightLabeled
      .set(chain.getHeight(header.hash).getOrElse(-1).toDouble)

    for {
      isCanonical <- chain.isCanonical(header.hash)
      blockTime   <- chain.getBlockTime(header)
    } {
      // as the genesis time is 0, the first block's blockTime is very large so we exclude it
      // TODO: remove this check once we introduce proper genesis timestamp
      if (isCanonical && (blockTime < Duration.ofDaysUnsafe(1))) {
        blockDurationMilliSecondsLabeled.observe(blockTime.millis.toDouble)
      }
    }

    val hashRate = HashRate.from(header.target, consensusConfig.blockTargetTime)
    targetHashRateHertzLabeled.set(hashRate.value.doubleValue)

    chain
  }

  protected def chainIndexFromString = chainIndex.from.value.toString
  protected def chainIndexToString   = chainIndex.to.value.toString

  private val blockDurationMilliSecondsLabeled = blockDurationMilliSeconds
    .labels(chainIndexFromString, chainIndexToString)

  private val blockCurrentHeightLabeled = blockCurrentHeight
    .labels(chainIndexFromString, chainIndexToString)

  private val targetHashRateHertzLabeled = targetHashRateHertz
    .labels(chainIndexFromString, chainIndexToString)
}
