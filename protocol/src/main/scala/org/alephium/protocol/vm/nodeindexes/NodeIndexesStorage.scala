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

import org.alephium.io.KeyValueStorage
import org.alephium.protocol.model.{BlockHash, TransactionId, TxOutputRef}
import org.alephium.protocol.vm.event.LogStorage
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage.TxIdBlockHashes
import org.alephium.protocol.vm.subcontractindex.SubContractIndexStorage
import org.alephium.serde.{avectorSerde, Serde}
import org.alephium.util.AVector

// format: off
final case class NodeIndexesStorage(
    logStorage: LogStorage,
    txOutputRefIndexStorage: TxOutputRefIndexStorage[KeyValueStorage[TxOutputRef.Key, TxIdBlockHashes]],
    subContractIndexStorage: Option[SubContractIndexStorage]
)
// format: on
object NodeIndexesStorage {
  type TxIdBlockHashes = (TransactionId, AVector[BlockHash])

  implicit val txIdBlockHashesSerde: Serde[TxIdBlockHashes] =
    Serde.tuple2[TransactionId, AVector[BlockHash]](
      implicitly[Serde[TransactionId]],
      avectorSerde[BlockHash]
    )
}
