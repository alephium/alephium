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

package org.alephium.protocol.vm

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.alephium.io.IOError
import org.alephium.protocol.model.{ContractId, ContractOutput, ContractOutputRef, HardFork}
import org.alephium.util.{AVector, EitherF}

trait ContractPool extends CostStrategy {
  import ContractPool._

  def getHardFork(): HardFork

  def worldState: WorldState.Staging

  lazy val contractPool      = mutable.Map.empty[ContractId, StatefulContractObject]
  lazy val assetStatus       = mutable.Map.empty[ContractId, ContractAssetStatus]
  lazy val contractBlockList = mutable.Set.empty[ContractId]

  lazy val contractInputs: ArrayBuffer[(ContractOutputRef, ContractOutput)] = ArrayBuffer.empty

  def loadContractObj(contractId: ContractId): ExeResult[StatefulContractObject] = {
    contractPool.get(contractId) match {
      case Some(obj) => Right(obj)
      case None =>
        for {
          _   <- checkIfBlocked(contractId)
          obj <- loadFromWorldState(contractId)
          _   <- chargeContractLoad(obj)
          _   <- add(contractId, obj)
        } yield obj
    }
  }

  def blockContractLoad(contractId: ContractId): Unit = {
    if (getHardFork().isLemanEnabled()) {
      contractBlockList.add(contractId)
      ()
    }
  }

  def checkIfBlocked(contractId: ContractId): ExeResult[Unit] = {
    if (getHardFork().isLemanEnabled() && contractBlockList.contains(contractId)) {
      failed(ContractLoadDisallowed(contractId))
    } else {
      okay
    }
  }

  private def loadFromWorldState(contractId: ContractId): ExeResult[StatefulContractObject] = {
    worldState.getContractObj(contractId) match {
      case Right(obj)                   => Right(obj)
      case Left(_: IOError.KeyNotFound) => failed(NonExistContract(contractId))
      case Left(e)                      => ioFailed(IOErrorLoadContract(e))
    }
  }

  private var contractFieldSize = 0
  private def add(contractId: ContractId, obj: StatefulContractObject): ExeResult[Unit] = {
    contractFieldSize += (obj.immFields.length + obj.initialMutFields.length)
    if (contractPool.size >= contractPoolMaxSize) {
      failed(ContractPoolOverflow)
    } else if (contractFieldSize > contractFieldMaxSize) {
      failed(ContractFieldOverflow)
    } else {
      contractPool.addOne(contractId -> obj)
      okay
    }
  }

  def removeContract(contractId: ContractId): ExeResult[Unit] = {
    for {
      _ <-
        worldState.removeContractForVM(contractId).left.map(e => Left(IOErrorRemoveContract(e)))
      _ <- markAssetFlushed(contractId)
    } yield {
      removeContractFromCache(contractId)
    }
  }

  def removeContractFromCache(contractId: ContractId): Unit = {
    contractPool -= contractId
  }

  def updateContractStates(): ExeResult[Unit] = {
    EitherF.foreachTry(contractPool) { case (contractId, contractObj) =>
      if (contractObj.isUpdated) {
        for {
          _ <- chargeContractStateUpdate(contractObj)
          _ <- updateMutableFields(contractId, AVector.from(contractObj.mutFields))
        } yield ()
      } else {
        Right(())
      }
    }
  }

  def removeOutdatedContractAssets(): ExeResult[Unit] = {
    EitherF.foreachTry(contractInputs) { input =>
      worldState.removeAsset(input._1).left.map(e => Left(IOErrorRemoveContractAsset(e)))
    }
  }

  private def updateMutableFields(
      contractId: ContractId,
      newMutFields: AVector[Val]
  ): ExeResult[Unit] = {
    worldState
      .updateContractUnsafe(contractId, newMutFields)
      .left
      .map(e => Left(IOErrorUpdateState(e)))
  }

  def useContractAssets(contractId: ContractId): ExeResult[MutBalancesPerLockup] = {
    for {
      _ <- chargeContractInput()
      balances <- worldState
        .loadContractAssets(contractId)
        .map { case (contractOutputRef, contractAsset) =>
          contractInputs.addOne(contractOutputRef -> contractAsset)
          MutBalancesPerLockup.from(contractAsset)
        }
        .left
        .map(e => Left(IOErrorLoadContract(e)))
      _ <- markAssetInUsing(contractId)
    } yield balances
  }

  def markAssetInUsing(contractId: ContractId): ExeResult[Unit] = {
    if (assetStatus.contains(contractId)) {
      failed(ContractAssetAlreadyInUsing)
    } else {
      assetStatus.put(contractId, ContractAssetInUsing)
      Right(())
    }
  }

  def markAssetFlushed(contractId: ContractId): ExeResult[Unit] = {
    assetStatus.get(contractId) match {
      case Some(ContractAssetInUsing) => Right(assetStatus.update(contractId, ContractAssetFlushed))
      case Some(ContractAssetFlushed) => failed(ContractAssetAlreadyFlushed)
      case None                       => failed(ContractAssetUnloaded)
    }
  }

  def checkAllAssetsFlushed(): ExeResult[Unit] = {
    if (assetStatus.forall(_._2 == ContractAssetFlushed)) {
      Right(())
    } else {
      failed(EmptyContractAsset)
    }
  }
}

object ContractPool {
  sealed trait ContractAssetStatus
  case object ContractAssetInUsing extends ContractAssetStatus
  case object ContractAssetFlushed extends ContractAssetStatus
}
