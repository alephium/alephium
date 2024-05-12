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

package org.alephium.protocol.vm.subcontract

import scala.collection.mutable

import org.alephium.io.{CachedKVStorage, IOResult, KeyValueStorage}
import org.alephium.protocol.model.ContractId

final class CachedSubContractIndexPageCounter(
    val counter: CachedKVStorage[ContractId, Int],
    val initialCounts: mutable.Map[ContractId, Int]
) extends MutableSubContractIndex.SubContractsPageCounter {

  def getInitialCount(contractId: ContractId): IOResult[Int] = {
    initialCounts.get(contractId) match {
      case Some(count) =>
        Right(count)
      case None =>
        counter.getOpt(contractId).map { countOpt =>
          val count = countOpt.getOrElse(0)
          initialCounts.put(contractId, count)
          count
        }
    }
  }

  def persist(): IOResult[Unit] = counter.persist().map(_ => ())

  def staging(): StagingSubContractIndexPageCounter = {
    new StagingSubContractIndexPageCounter(this.counter.staging(), this)
  }
}

object CachedSubContractIndexPageCounter {
  def from(storage: KeyValueStorage[ContractId, Int]): CachedSubContractIndexPageCounter = {
    new CachedSubContractIndexPageCounter(CachedKVStorage.from(storage), mutable.HashMap.empty)
  }
}
