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
import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, PersistedOutput, UnpersistedBlockOutput}
import org.alephium.flow.mempool.MemPool
import org.alephium.io.IOResult
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, WorldState}
import org.alephium.util.AVector

trait BlockFlowGroupView[WS <: WorldState[_, _, _, _]] {
  def worldState: WS

  def getPreOutput(outputRef: TxOutputRef): IOResult[Option[TxOutput]]

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getAsset(outputRef: TxOutputRef): IOResult[Option[AssetOutput]] = {
    // we use asInstanceOf for optimization
    getPreOutput(outputRef) match {
      case Right(Some(_: ContractOutput)) => Left(WorldState.expectedAssetError)
      case result                         => result.asInstanceOf[IOResult[Option[AssetOutput]]]
    }
  }

  def getPreOutputs(inputs: AVector[TxInput]): IOResult[Option[AVector[AssetOutput]]] = {
    inputs.foldE(Option(AVector.ofCapacity[AssetOutput](inputs.length))) {
      case (Some(outputs), input) => getAsset(input.outputRef).map(_.map(outputs :+ _))
      case (None, _)              => Right(None)
    }
  }

  def getPreOutputs(tx: Transaction): IOResult[Option[AVector[TxOutput]]] = {
    getPreOutputs(tx.unsigned.inputs).flatMap {
      case Some(outputs) =>
        tx.contractInputs.foldE(Option(outputs.as[TxOutput])) {
          case (Some(outputs), input) => getPreOutput(input).map(_.map(outputs :+ _))
          case (None, _)              => Right(None)
        }
      case None => Right(None)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getPrevAssetOutputs(
      inputs: AVector[AssetOutputRef]
  ): IOResult[Option[AVector[(AssetOutputRef, AssetOutput)]]] = {
    inputs.foldE(Option(AVector.ofCapacity[(AssetOutputRef, AssetOutput)](inputs.length))) {
      case (Some(outputs), input) =>
        getPreOutput(input).map(_.map { output =>
          val assetOutput = output.asInstanceOf[AssetOutput]
          outputs :+ (input -> assetOutput)
        })
      case (None, _) => Right(None)
    }
  }

  def getPreContractOutputs(
      inputs: AVector[ContractOutputRef]
  ): IOResult[Option[AVector[TxOutput]]] = {
    inputs.foldE(Option(AVector.ofCapacity[TxOutput](inputs.length))) {
      case (Some(outputs), input) => getPreOutput(input).map(_.map(outputs :+ _))
      case (None, _)              => Right(None)
    }
  }

  def getRelevantUtxos(
      lockupScript: LockupScript.Asset,
      maxUtxosToRead: Int
  ): IOResult[AVector[AssetOutputInfo]]

  def getContractUtxos(lockupScript: LockupScript.P2C): IOResult[ContractOutput] = {
    worldState.getContractAsset(lockupScript.contractId)
  }
}

object BlockFlowGroupView {
  def onlyBlocks[WS <: WorldState[_, _, _, _]](
      worldState: WS,
      blockCaches: AVector[BlockCache]
  ): BlockFlowGroupView[WS] = {
    new Impl0[WS](worldState, blockCaches)
  }

  def includePool[WS <: WorldState[_, _, _, _]](
      worldState: WS,
      blockCaches: AVector[BlockCache],
      mempool: MemPool
  ): BlockFlowGroupView[WS] = {
    new Impl1[WS](worldState, blockCaches, mempool)
  }

  private class Impl0[WS <: WorldState[_, _, _, _]](
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

    private def getPersistedUtxos(
        lockupScript: LockupScript.Asset,
        maxUtxosToRead: Int
    ): IOResult[AVector[AssetOutputInfo]] = {
      for {
        persistedUtxos <- worldState
          .getAssetOutputs(
            lockupScript.hintBytes,
            maxUtxosToRead,
            (_, output) => output.lockupScript == lockupScript
          )
          .map(
            _.map(p => AssetOutputInfo(p._1, p._2, PersistedOutput))
          )
      } yield persistedUtxos
    }

    private def getUtxosInCache(
        lockupScript: LockupScript,
        persistedUtxos: AVector[AssetOutputInfo]
    ): (AVector[AssetOutputRef], AVector[AssetOutputInfo]) = {
      val persistedUtxosIndx = Set.from[TxOutputRef](persistedUtxos.toIterable.map(_.ref))
      val usedUtxos = blockCaches.flatMap[AssetOutputRef] { blockCache =>
        AVector.from(
          blockCache.inputs.view
            .filter(input => persistedUtxosIndx.contains(input))
            .map(_.asInstanceOf[AssetOutputRef])
        )
      }
      val outputsInCaches = blockCaches.flatMap { blockCache =>
        AVector
          .from(
            blockCache.relatedOutputs.view
              .filter(p => ableToUse(p._2, lockupScript) && p._1.isAssetType && p._2.isAsset)
              .map(p =>
                AssetOutputInfo(
                  p._1.asInstanceOf[AssetOutputRef],
                  p._2.asInstanceOf[AssetOutput],
                  UnpersistedBlockOutput
                )
              )
          )
      }
      val newUtxos = outputsInCaches.filter(info => !TxUtils.isSpent(blockCaches, info.ref))
      (usedUtxos, newUtxos)
    }

    private def ableToUse(
        output: TxOutput,
        lockupScript: LockupScript
    ): Boolean =
      output match {
        case o: AssetOutput    => o.lockupScript == lockupScript
        case _: ContractOutput => false
      }

    private def mergeUtxos(
        persistedUtxos: AVector[AssetOutputInfo],
        usedInCache: AVector[AssetOutputRef],
        newInCache: AVector[AssetOutputInfo]
    ): AVector[AssetOutputInfo] = {
      persistedUtxos.filter(p => !usedInCache.contains(p.ref)) ++ newInCache
    }

    def getRelevantUtxos(
        lockupScript: LockupScript.Asset,
        maxUtxosToRead: Int
    ): IOResult[AVector[AssetOutputInfo]] = {
      getPersistedUtxos(lockupScript, maxUtxosToRead).map { persistedUtxos =>
        val cachedResult = getUtxosInCache(lockupScript, persistedUtxos)
        mergeUtxos(persistedUtxos, cachedResult._1, cachedResult._2)
      }
    }
  }

  private class Impl1[WS <: WorldState[_, _, _, _]](
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

    override def getRelevantUtxos(
        lockupScript: LockupScript.Asset,
        maxUtxosToRead: Int
    ): IOResult[AVector[AssetOutputInfo]] = {
      super.getRelevantUtxos(lockupScript, maxUtxosToRead).map { utxosInBlocks =>
        mempool.getRelevantUtxos(lockupScript, utxosInBlocks)
      }
    }
  }
}
