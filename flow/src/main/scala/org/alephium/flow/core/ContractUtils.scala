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

import org.alephium.protocol.model.ContractId
import org.alephium.protocol.model.GroupIndex

trait ContractUtils { Self: FlowUtils =>
  def getGroupForContract(contractId: ContractId): Either[String, GroupIndex] = {
    val failure: Either[String, GroupIndex] = Left("Group not found.").withRight[GroupIndex]
    brokerConfig.groupRange.foldLeft(failure) { case (prevResult, currentGroup: Int) =>
      prevResult match {
        case Right(prevResult) => Right(prevResult)
        case Left(_) =>
          getContractGroup(contractId, GroupIndex.unsafe(currentGroup))
      }
    }
  }

  def getContractGroup(
      contractId: ContractId,
      groupIndex: GroupIndex
  ): Either[String, GroupIndex] = {
    val searchResult = for {
      worldState <- getBestPersistedWorldState(groupIndex)
      existed    <- worldState.contractState.exists(contractId)
    } yield existed

    searchResult match {
      case Right(true)  => Right(groupIndex)
      case Right(false) => Left("Group not found. Please check another broker")
      case Left(error)  => Left(s"Failed in IO: $error")
    }
  }
}
