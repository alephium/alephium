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

import org.alephium.io.StagingKVStorage
import org.alephium.protocol.model.ContractId
import org.alephium.protocol.vm.nodeindexes.StagingPageCounter

final class StagingSubContractIndex(
    val parentContractIndexState: StagingKVStorage[ContractId, ContractId],
    val subContractIndexState: StagingKVStorage[SubContractIndexStateId, SubContractIndexState],
    val subContractIndexPageCounterState: StagingPageCounter[ContractId]
) extends MutableSubContractIndex {
  def rollback(): Unit = {
    parentContractIndexState.rollback()
    subContractIndexState.rollback()
    subContractIndexPageCounterState.rollback()
  }

  def commit(): Unit = {
    parentContractIndexState.commit()
    subContractIndexState.commit()
    subContractIndexPageCounterState.commit()
  }
}
