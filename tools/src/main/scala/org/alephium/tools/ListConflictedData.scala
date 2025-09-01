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

package org.alephium.tools

import org.alephium.api._
import org.alephium.api.UtilJson._
import org.alephium.flow.client.Node
import org.alephium.flow.setting.Platform
import org.alephium.io.{RocksDBKeyValueStorage}
import org.alephium.json.Json._
import org.alephium.json.Json.{ReadWriter => RW}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.nodeindexes._
import org.alephium.util.AVector

@SuppressWarnings(
  Array(
    "org.wartremover.warts.ToString",
    "org.wartremover.warts.PublicInference",
    "org.wartremover.warts.OptionPartial"
  )
)
object ListConflictedData extends App with ApiModelCodec {

  implicit override val groupConfig: GroupConfig =
    new GroupConfig {
      override def groups: Int = 4
    }

  final case class Data(
      block: BlockHash,
      conflictedTxsSource: AVector[ConflictedTxsSource],
      conflictedTxs: Option[AVector[TransactionId]]
  )

  implicit val conflictedTxsSourceRW: RW[ConflictedTxsSource] = macroRW
  implicit val dataRw: RW[Data]                               = macroRW

  val rootPath       = Platform.getRootPath()
  val (blockFlow, _) = Node.buildBlockFlowUnsafe(rootPath)

  val storage = blockFlow.conflictedTxsStorage.conflictedTxsReversedIndex
    .asInstanceOf[RocksDBKeyValueStorage[BlockHash, AVector[ConflictedTxsSource]]]

  val buffer = scala.collection.mutable.ArrayBuffer.empty[(BlockHash, AVector[ConflictedTxsSource])]
  storage.iterate { (block, txs) =>
    if (!ChainIndex.from(block).isIntraGroup) buffer.append((block, txs))
  }

  val data = buffer.toSeq.map { case (block, txs) =>
    val conflictedTxs = blockFlow.getConflictedTxsFromBlock(block).toOption.get
    Data(block, txs, conflictedTxs)
  }

  val json = write(data)

  print(json)
}
