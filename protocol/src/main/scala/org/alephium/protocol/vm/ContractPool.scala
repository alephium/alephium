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
import org.alephium.protocol.model.{ContractId, ContractOutput, ContractOutputRef}
import org.alephium.util.{AVector, EitherF}

trait ContractPool extends CostStrategy {
  import ContractPool._

  def worldState: WorldState.Staging

  lazy val contractPool = mutable.Map.empty[ContractId, StatefulContractObject]
  lazy val assetStatus  = mutable.Map.empty[ContractId, ContractAssetStatus]

  lazy val contractInputs: ArrayBuffer[(ContractOutputRef, ContractOutput)] = ArrayBuffer.empty

  def loadContractObj(contractKey: ContractId): ExeResult[StatefulContractObject] = {
    contractPool.get(contractKey) match {
      case Some(obj) => Right(obj)
      case None =>
        for {
          obj <- loadFromWorldState(contractKey)
          _   <- chargeContractLoad(obj)
          _   <- add(contractKey, obj)
        } yield obj
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
  private def add(contractKey: ContractId, obj: StatefulContractObject): ExeResult[Unit] = {
    contractFieldSize += obj.initialFields.length
    if (contractPool.size >= contractPoolMaxSize) {
      failed(ContractPoolOverflow)
    } else if (contractFieldSize > contractFieldMaxSize) {
      failed(ContractFieldOverflow)
    } else {
      contractPool.addOne(contractKey -> obj)
      okay
    }
  }

  def removeContract(contractId: ContractId): ExeResult[Unit] = {
    for {
      _ <-
        worldState.removeContractState(contractId).left.map(e => Left(IOErrorRemoveContract(e)))
      _ <- markAssetFlushed(contractId)
    } yield {
      contractPool.remove(contractId)
      ()
    }
  }

  def updateContractStates(): ExeResult[Unit] = {
    EitherF.foreachTry(contractPool) { case (contractKey, contractObj) =>
      if (contractObj.isUpdated) {
        for {
          _ <- chargeContractStateUpdate(contractObj)
          _ <- updateState(contractKey, AVector.from(contractObj.fields))
        } yield ()
      } else {
        Right(())
      }
    }
  }

  private def updateState(contractKey: ContractId, state: AVector[Val]): ExeResult[Unit] = {
    worldState.updateContractUnsafe(contractKey, state).left.map(e => Left(IOErrorUpdateState(e)))
  }

  // note: we don't charge gas here as it's charged by tx input already
  def useContractAsset(contractId: ContractId): ExeResult[BalancesPerLockup] = {
    for {
      _ <- chargeContractInput()
      balances <- worldState
        .useContractAsset(contractId)
        .map { case (contractOutputRef, contractAsset) =>
          contractInputs.addOne(contractOutputRef -> contractAsset)
          BalancesPerLockup.from(contractAsset)
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
