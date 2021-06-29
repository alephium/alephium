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

package org.alephium.flow.core

import org.alephium.flow.core.BlockFlowState.BlockCache
import org.alephium.flow.mempool.MemPool
import org.alephium.io.IOResult
import org.alephium.protocol.model._
import org.alephium.protocol.vm.WorldState
import org.alephium.util.AVector

trait BlockFlowGroupView[WS <: WorldState[_]] {
  def worldState: WS

  def getPreOutput(outputRef: TxOutputRef): IOResult[Option[TxOutput]]

  def getPreOutputs(inputs: AVector[TxInput]): IOResult[Option[AVector[TxOutput]]] = {
    inputs.foldE(Option(AVector.ofSize[TxOutput](inputs.length))) {
      case (Some(outputs), input) => getPreOutput(input.outputRef).map(_.map(outputs :+ _))
      case (None, _)              => Right(None)
    }
  }

  def getPreOutputs(tx: Transaction): IOResult[Option[AVector[TxOutput]]] = {
    getPreOutputs(tx.unsigned.inputs).flatMap {
      case Some(outputs) =>
        tx.contractInputs.foldE(Option(outputs)) {
          case (Some(outputs), input) => getPreOutput(input).map(_.map(outputs :+ _))
          case (None, _)              => Right(None)
        }
      case None => Right(None)
    }
  }

  def getPreContractOutputs(
      inputs: AVector[ContractOutputRef]
  ): IOResult[Option[AVector[TxOutput]]] = {
    inputs.foldE(Option(AVector.ofSize[TxOutput](inputs.length))) {
      case (Some(outputs), input) => getPreOutput(input).map(_.map(outputs :+ _))
      case (None, _)              => Right(None)
    }
  }
}

object BlockFlowGroupView {
  def onlyBlocks[WS <: WorldState[_]](
      worldState: WS,
      blockCaches: AVector[BlockCache]
  ): BlockFlowGroupView[WS] = {
    new Impl0[WS](worldState, blockCaches)
  }

  def includePool[WS <: WorldState[_]](
      worldState: WS,
      blockCaches: AVector[BlockCache],
      mempool: MemPool
  ): BlockFlowGroupView[WS] = {
    new Impl1[WS](worldState, blockCaches, mempool)
  }

  private class Impl0[WS <: WorldState[_]](
      _worldState: WS,
      blockCaches: AVector[BlockCache]
  ) extends BlockFlowGroupView[WS] {
    def worldState: WS = _worldState

    def getPreOutput(outputRef: TxOutputRef): IOResult[Option[TxOutput]] = {
      if (TxUtils.isSpent(blockCaches, outputRef)) {
        Right(None)
      } else {
        worldState.getOutputOpt(outputRef).map {
          case Some(output) => Some(output)
          case None =>
            val index = blockCaches.indexWhere(_.relatedOutputs.contains(outputRef))
            if (index != -1) {
              Some(blockCaches(index).relatedOutputs(outputRef))
            } else {
              None
            }
        }
      }
    }
  }

  private class Impl1[WS <: WorldState[_]](
      worldState: WS,
      blockCaches: AVector[BlockCache],
      mempool: MemPool
  ) extends Impl0[WS](worldState, blockCaches) {
    override def getPreOutput(outputRef: TxOutputRef): IOResult[Option[TxOutput]] = {
      if (mempool.isSpent(outputRef)) {
        Right(None)
      } else {
        mempool.getOutput(outputRef) match {
          case Some(output) => Right(Some(output))
          case None         => super.getPreOutput(outputRef)
        }
      }
    }
  }
}
