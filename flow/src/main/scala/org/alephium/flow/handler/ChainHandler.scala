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
import org.alephium.io.IOError
import org.alephium.protocol.BlockHash
import org.alephium.protocol.model.{ChainIndex, FlowData}
import org.alephium.util._

object ChainHandler {
  trait Event
}

abstract class ChainHandler[T <: FlowData, S <: InvalidStatus, Command](blockFlow: BlockFlow,
                                                                        val chainIndex: ChainIndex,
                                                                        validator: Validation[T, S])
    extends IOBaseActor {
  import ChainHandler.Event

  def handleData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    assume(!blockFlow.containsUnsafe(data.hash))
    log.debug(s"Try to add ${data.shortHex}")
    validator.validate(data, blockFlow) match {
      case Left(Left(e))                 => handleIOError(data.hash, broker, e)
      case Left(Right(x: InvalidStatus)) => handleInvalidData(data, broker, x)
      case Right(_)                      => handleValidData(data, broker, origin)
    }
  }

  def handleIOError(hash: BlockHash,
                    broker: ActorRefT[ChainHandler.Event],
                    error: IOError): Unit = {
    log.error(s"IO failed in block/header ${hash.shortHex} validation: ${error.toString}")
    broker ! dataAddingFailed()
  }

  def handleInvalidData(data: T,
                        broker: ActorRefT[ChainHandler.Event],
                        status: InvalidStatus): Unit = {
    log.warning(s"Invalid block/blockheader ${data.shortHex}: $status")
    sender() ! DependencyHandler.Invalid(data.hash)
    broker ! dataInvalid(data)
  }

  def handleValidData(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit = {
    log.info(s"${data.shortHex} is validated")
    if (blockFlow.isRecent(data)) {
      broadcast(data, origin)
    }
    addToFlowHandler(data, broker, origin)
  }

  def broadcast(data: T, origin: DataOrigin): Unit

  def addToFlowHandler(data: T, broker: ActorRefT[ChainHandler.Event], origin: DataOrigin): Unit

  def dataAddingFailed(): Event

  def dataInvalid(data: T): Event
}
