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
import org.alephium.protocol.model.{TransactionId, TxOutputRef}
import org.alephium.protocol.vm.nodeindexes.NodeIndexesStorage.{
  TxIdTxOutputLocators,
  TxOutputLocator
}
import org.alephium.util.AVector

// format: off
final case class TxOutputRefIndexStorage[+T <: MutableKV[TxOutputRef.Key, TxIdTxOutputLocators, Unit]](
    value: Option[T]
) {
// format: on
  def store(
      outputRef: TxOutputRef.Key,
      txId: TransactionId,
      txOutputLocatorOpt: Option[TxOutputLocator]
  ): IOResult[Unit] = {
    (value, txOutputLocatorOpt) match {
      case (Some(storage), Some(txOutputLocator)) =>
        storage.getOpt(outputRef).flatMap {
          case Some((txId, txOutputLocators)) =>
            storage.put(outputRef, (txId, txOutputLocators :+ txOutputLocator))
          case None =>
            storage.put(outputRef, (txId, AVector(txOutputLocator)))
        }
      case _ => Right(())
    }
  }

  def getOpt(outputRef: TxOutputRef.Key): IOResult[Option[TxIdTxOutputLocators]] = {
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
