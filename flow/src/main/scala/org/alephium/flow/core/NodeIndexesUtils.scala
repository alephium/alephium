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

import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.model.{BlockHash, ContractId, TransactionId, TxOutputRef}
import org.alephium.protocol.vm.subcontractindex.SubContractIndexStateId
import org.alephium.util.AVector

trait NodeIndexesUtils { Self: FlowUtils =>
  def getTxIdFromOutputRef(
      outputRef: TxOutputRef
  ): IOResult[Option[(TransactionId, AVector[BlockHash])]] = {
    txOutputRefIndexStorage(outputRef.hint.groupIndex).getOpt(outputRef.key)
  }

  def getParentContractId(contractId: ContractId): IOResult[Option[ContractId]] = {
    subContractIndexStorage.flatMap(_.parentContractIndexState.getOpt(contractId))
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
      subContractIndexStorage match {
        case Right(storage) =>
          storage.subContractIndexStates.getOpt(subContractIndexStateId) match {
            case Right(Some(subContractIndexState)) =>
              allSubContracts ++= subContractIndexState.subContracts
              nextCount = subContractIndexStateId.counter + 1
              if (nextCount < end) {
                rec(SubContractIndexStateId(subContractIndexStateId.contractId, nextCount))
              } else {
                Right(())
              }
            case Right(None) =>
              Left(
                IOError.keyNotFound(
                  s"Can not find sub-contracts for ${subContractIndexStateId.contractId.toHexString} at count ${subContractIndexStateId.counter}"
                )
              )
            case Left(error) =>
              Left(error)

          }
        case Left(error) =>
          Left(error)
      }
    }

    rec(SubContractIndexStateId(contractId, nextCount)).map(_ =>
      (nextCount, AVector.from(allSubContracts))
    )
  }

  def getSubContractsCurrentCount(parentContractId: ContractId): IOResult[Option[Int]] = {
    subContractIndexStorage.flatMap(_.subContractIndexCounterState.getOpt(parentContractId))
  }
}
