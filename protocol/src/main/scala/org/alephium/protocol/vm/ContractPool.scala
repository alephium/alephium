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
  def worldState: WorldState.Staging

  val pool = mutable.Map.empty[ContractId, StatefulContractObject]

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
    worldState.getContractObj(contractKey).left.map[ExeFailure](IOErrorLoadContract)
  }

  private def add(contractKey: ContractId, obj: StatefulContractObject): ExeResult[Unit] = {
    if (pool.size < contractPoolMaxSize) {
      pool.addOne(contractKey -> obj)
      Right(())
    } else {
      Left(ContractPoolOverflow)
    }
  }

  def commitContractStates(): ExeResult[Unit] = {
    EitherF.foreachTry(pool) {
      case (contractKey, contractObj) =>
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
    worldState.updateContract(contractKey, state).left.map(IOErrorUpdateState)
  }
}

object ContractPool {
  final case class State(state: mutable.ArraySeq[Val]) extends AnyVal
}
