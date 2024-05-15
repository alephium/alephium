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

package org.alephium.protocol.vm.subcontractindex

import org.alephium.io.{IOResult, MutableKV}
import org.alephium.protocol.model.ContractId
import org.alephium.util.AVector

trait MutableSubContractIndex {
  def parentContractIndexState: MutableKV[ContractId, ContractId, Unit]
  def subContractIndexState: MutableKV[SubContractIndexStateId, SubContractIndexState, Unit]
  def subContractIndexPageCounterState: MutableSubContractIndex.SubContractsPageCounter

  def createSubContractIndexes(
      parentContractId: ContractId,
      subContractId: ContractId
  ): IOResult[Unit] = {
    for {
      _ <- parentContractIndexState.put(subContractId, parentContractId)
      _ <- indexSubContracts(parentContractId, subContractId)
    } yield ()
  }

  def indexSubContracts(
      parentContract: ContractId,
      subContract: ContractId
  ): IOResult[Unit] = {
    for {
      initialCount <- subContractIndexPageCounterState.getInitialCount(parentContract)
      id = SubContractIndexStateId(parentContract, initialCount)
      subContractIndexStateOpt <- subContractIndexState.getOpt(id)
      _ <- subContractIndexStateOpt match {
        case Some(SubContractIndexState(subContracts)) =>
          subContractIndexState.put(id, SubContractIndexState(subContracts :+ subContract))
        case None =>
          subContractIndexState.put(id, SubContractIndexState(AVector(subContract)))
      }
      _ <- subContractIndexPageCounterState.counter.put(parentContract, initialCount + 1)
    } yield ()
  }
}

object MutableSubContractIndex {
  trait SubContractsPageCounter {
    def counter: MutableKV[ContractId, Int, Unit]
    def getInitialCount(key: ContractId): IOResult[Int]
  }
}
