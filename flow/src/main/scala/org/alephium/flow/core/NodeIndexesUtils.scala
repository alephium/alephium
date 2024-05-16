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

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.alephium.io.IOResult
import org.alephium.protocol.model.{AssetOutputRef, TransactionId}
import org.alephium.protocol.model.ContractId
import org.alephium.protocol.vm.subcontractindex.SubContractIndexStateId
import org.alephium.util.AVector

trait NodeIndexesUtils { Self: FlowUtils =>
  def getTxIdFromOutputRef(
      outputRef: AssetOutputRef
  ): IOResult[Option[TransactionId]] = {
    for {
      blockFlowGroupView <- getMutableGroupView(outputRef.hint.groupIndex)
      result             <- blockFlowGroupView.getTxIdFromOutputRef(outputRef)
    } yield result
  }

  def getParentContractId(contractId: ContractId): IOResult[Option[ContractId]] = {
    subContractIndexStorage.parentContractIndexState.getOpt(contractId)
  }

  def getSubContractIds(
      contractId: ContractId,
      start: Int,
      end: Int
  ): IOResult[(Int, AVector[ContractId])] = {
    assume(start < end)
    val allSubContracts: ArrayBuffer[ContractId] = ArrayBuffer.empty
    var nextCount                                = start

    @tailrec
    def rec(
        subContractIndexStateId: SubContractIndexStateId
    ): IOResult[Unit] = {
      subContractIndexStorage.subContractIndexStates.getOpt(subContractIndexStateId) match {
        case Right(Some(subContractIndexState)) =>
          allSubContracts ++= subContractIndexState.subContracts
          nextCount = subContractIndexStateId.counter + 1
          if (nextCount < end) {
            rec(SubContractIndexStateId(subContractIndexStateId.contractId, nextCount))
          } else {
            Right(())
          }
        case Right(None) =>
          Right(())
        case Left(error) =>
          Left(error)
      }
    }

    rec(SubContractIndexStateId(contractId, nextCount)).map(_ =>
      (nextCount, AVector.from(allSubContracts))
    )
  }

  def getSubContractsCurrentCount(parentContractId: ContractId): IOResult[Option[Int]] = {
    subContractIndexStorage.subContractIndexCounterState.getOpt(parentContractId)
  }
}
