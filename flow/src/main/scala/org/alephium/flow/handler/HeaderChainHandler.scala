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

import akka.actor.Props
import io.prometheus.client.{Counter, Gauge, Histogram}

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.validation._
import org.alephium.io.IOResult
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfigs, NetworkConfig}
import org.alephium.protocol.model.{BlockHash, BlockHeader, ChainIndex}
import org.alephium.util.ActorRefT

object HeaderChainHandler {
  def props(
      blockFlow: BlockFlow,
      chainIndex: ChainIndex
  )(implicit
      brokerConfig: BrokerConfig,
      consensusConfigs: ConsensusConfigs,
      networkConfig: NetworkConfig
  ): Props =
    Props(new HeaderChainHandler(blockFlow, chainIndex))

  sealed trait Command
  final case class Validate(
      header: BlockHeader,
      broker: ActorRefT[ChainHandler.Event],
      origin: DataOrigin
  ) extends Command

  sealed trait Event                              extends ChainHandler.Event
  final case class HeaderAdded(hash: BlockHash)   extends Event
  case object HeaderAddingFailed                  extends Event
  final case class InvalidHeader(hash: BlockHash) extends Event

  val headersTotal: Gauge = Gauge
    .build(
      "alephium_headers_total",
      "Total number of headers"
    )
    .labelNames("chain_from", "chain_to")
    .register()

  val headersReceivedTotal: Counter = Counter
    .build(
      "alephium_headers_received_total",
      "Total number of headers received"
    )
    .labelNames("chain_from", "chain_to")
    .register()
}

class HeaderChainHandler(
    blockFlow: BlockFlow,
    chainIndex: ChainIndex
)(implicit
    brokerConfig: BrokerConfig,
    val consensusConfigs: ConsensusConfigs,
    val networkConfig: NetworkConfig
) extends ChainHandler[BlockHeader, InvalidHeaderStatus, Unit, HeaderValidation](
      blockFlow,
      chainIndex,
      HeaderValidation.build
    ) {
  import HeaderChainHandler._

  override def receive: Receive = { case Validate(header, broker, origin) =>
    handleData(header, broker, origin)
  }

  def validateWithSideEffect(
      header: BlockHeader,
      origin: DataOrigin
  ): ValidationResult[InvalidHeaderStatus, Unit] = {
    validator.validate(header, blockFlow)
  }

  override def dataAddingFailed(): Event = HeaderAddingFailed

  override def dataInvalid(data: BlockHeader, reason: InvalidHeaderStatus): Event =
    InvalidHeader(data.hash)

  override def addDataToBlockFlow(
      header: BlockHeader,
      validationSideEffect: Unit
  ): IOResult[Unit] = {
    blockFlow.add(header)
  }

  override def notifyBroker(broker: ActorRefT[ChainHandler.Event], data: BlockHeader): Unit = {
    broker ! HeaderChainHandler.HeaderAdded(data.hash)
  }

  override def show(header: BlockHeader): String = showHeader(header)

  private val headersTotalLabeled = headersTotal.labels(chainIndexFromString, chainIndexToString)
  private val headersReceivedTotalLabeled =
    headersReceivedTotal.labels(chainIndexFromString, chainIndexToString)
  override def measure(header: BlockHeader): Unit = {
    val chain = measureCommon(header)

    headersTotalLabeled.set(chain.numHashes.toDouble)
    headersReceivedTotalLabeled.inc()
  }

  val chainValidationTotalLabeled: Counter.Child =
    ChainHandler.chainValidationTotal.labels("BlockHeader")
  val chainValidationDurationMilliSecondsLabeled: Histogram.Child =
    ChainHandler.chainValidationDurationMilliSeconds.labels("BlockHeader")
}
