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

import org.alephium.io.{CachedKVStorage, IOResult}
import org.alephium.protocol.model.ContractId
import org.alephium.protocol.vm.nodeindexes.CachedPageCounter

final class CachedSubContractIndex(
    val parentContractIndexState: CachedKVStorage[ContractId, ContractId],
    val subContractIndexStates: CachedKVStorage[SubContractIndexStateId, SubContractIndexState],
    val subContractIndexCounterState: CachedPageCounter[ContractId],
    subContractIndexStorage: SubContractIndexStorage
) {
  def persist(): IOResult[SubContractIndexStorage] = {
    for {
      _ <- parentContractIndexState.persist()
      _ <- subContractIndexStates.persist()
      _ <- subContractIndexCounterState.persist()
    } yield subContractIndexStorage
  }

  def staging(): StagingSubContractIndex = new StagingSubContractIndex(
    parentContractIndexState.staging(),
    subContractIndexStates.staging(),
    subContractIndexCounterState.staging()
  )
}

object CachedSubContractIndex {
  @inline def from(
      subContractIndexStorage: SubContractIndexStorage
  ): CachedSubContractIndex = {
    new CachedSubContractIndex(
      CachedKVStorage.from(subContractIndexStorage.parentContractIndexState),
      CachedKVStorage.from(subContractIndexStorage.subContractIndexStates),
      CachedPageCounter.from(subContractIndexStorage.subContractIndexCounterState),
      subContractIndexStorage
    )
  }
}
