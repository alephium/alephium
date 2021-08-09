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

import org.alephium.protocol.model.ContractId
import org.alephium.util.{AVector, EitherF}

trait ContractPool extends CostStrategy {
  import ContractPool._

  def worldState: WorldState.Staging

  val pool        = mutable.Map.empty[ContractId, StatefulContractObject]
  val assetStatus = mutable.Map.empty[ContractId, ContractAssetStatus]

  def loadContract(contractKey: ContractId): ExeResult[StatefulContractObject] = {
    pool.get(contractKey) match {
      case Some(obj) => Right(obj)
      case None =>
        for {
          _   <- chargeContractLoad()
          obj <- loadFromWorldState(contractKey)
          _   <- add(contractKey, obj)
        } yield obj
    }
  }

  private def loadFromWorldState(contractKey: ContractId): ExeResult[StatefulContractObject] = {
    worldState.getContractObj(contractKey).left.map(e => Left(IOErrorLoadContract(e)))
  }

  private def add(contractKey: ContractId, obj: StatefulContractObject): ExeResult[Unit] = {
    if (pool.size < contractPoolMaxSize) {
      pool.addOne(contractKey -> obj)
      okay
    } else {
      failed(ContractPoolOverflow)
    }
  }

  def updateContractStates(): ExeResult[Unit] = {
    EitherF.foreachTry(pool) { case (contractKey, contractObj) =>
      if (contractObj.isUpdated) {
        for {
          _ <- chargeContractUpdate()
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

  def commitStates(): Unit = {
    worldState.commit()
    ()
  }
}

object ContractPool {
  sealed trait ContractAssetStatus
  case object ContractAssetInUsing extends ContractAssetStatus
  case object ContractAssetFlushed extends ContractAssetStatus
}
