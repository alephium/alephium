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

import org.alephium.io.{IOError, IOResult, MutableKV}
import org.alephium.protocol.model.{BlockHash, TransactionId, TxOutputRef}
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage.TxIdBlockHashes
import org.alephium.util.AVector

final case class TxOutputRefIndexStorage[+T <: MutableKV[TxOutputRef.Key, TxIdBlockHashes, Unit]](
    value: Option[T]
) {
  def store(
      outputRef: TxOutputRef.Key,
      txId: TransactionId,
      blockHashOpt: Option[BlockHash]
  ): IOResult[Unit] = {
    (value, blockHashOpt) match {
      case (Some(storage), Some(blockHash)) =>
        storage.getOpt(outputRef).flatMap {
          case Some((txId, blockHashes)) =>
            storage.put(outputRef, (txId, blockHashes :+ blockHash))
          case None =>
            storage.put(outputRef, (txId, AVector(blockHash)))
        }
      case _ => Right(())
    }
  }

  def getOpt(outputRef: TxOutputRef.Key): IOResult[Option[TxIdBlockHashes]] = {
    value match {
      case Some(storage) =>
        storage.getOpt(outputRef)
      case None =>
        Left(
          IOError.configError(
            "Please set `alephium.node.indexes.tx-output-ref-index = true` to query transaction id from transaction output reference"
          )
        )
    }
  }
}
