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
import org.alephium.protocol.vm.{BlockEnv, TxEnv}
import org.alephium.protocol.vm.event.LogStorage
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage.TxIdTxOutputLocators
import org.alephium.protocol.vm.subcontractindex.SubContractIndexStorage
import org.alephium.serde.{avectorSerde, intSerde, Serde}
import org.alephium.util.AVector
// format: off
final case class NodeIndexesStorage(
    logStorage: LogStorage,
    txOutputRefIndexStorage: Option[KeyValueStorage[TxOutputRef.Key, TxIdTxOutputLocators]],
    subContractIndexStorage: Option[SubContractIndexStorage]
)
// format: on
object NodeIndexesStorage {
  type TxIndex              = Int
  type TxOutputIndex        = Int
  type TxOutputLocator      = (BlockHash, TxIndex, TxOutputIndex)
  type TxIdTxOutputLocators = (TransactionId, AVector[TxOutputLocator])

  implicit val txOutputLocatorSerde: Serde[TxOutputLocator] =
    Serde.tuple3[BlockHash, Int, Int]

  implicit val txIdBlockHashesSerde: Serde[TxIdTxOutputLocators] =
    Serde.tuple2[TransactionId, AVector[TxOutputLocator]]

  object TxOutputLocator {
    def from(
        blockEnv: BlockEnv,
        txEnv: TxEnv,
        txOutputIndex: TxOutputIndex
    ): Option[TxOutputLocator] = {
      for {
        blockHash <- blockEnv.blockId
      } yield (blockHash, txEnv.txIndex, txOutputIndex)
    }
  }
}
