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

package org.alephium.protocol.vm.nodeindexes

import org.alephium.io.StagingKVStorage
import org.alephium.protocol.model.{TransactionId, TxOutputRef}
import org.alephium.protocol.vm.event.StagingLog
import org.alephium.protocol.vm.subcontractindex.StagingSubContractIndex

final case class StagingNodeIndexes(
    logState: StagingLog,
    txOutputRefIndexState: Option[StagingKVStorage[TxOutputRef.Key, TransactionId]],
    subContractIndexState: Option[StagingSubContractIndex]
) {
  def rollback(): Unit = {
    logState.rollback()
    txOutputRefIndexState.foreach(_.rollback())
    subContractIndexState.foreach(_.rollback())
    ()
  }

  def commit(): Unit = {
    logState.commit()
    txOutputRefIndexState.foreach(_.commit())
    subContractIndexState.foreach(_.commit())
    ()
  }
}
