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

import org.alephium.io.{IOResult, MutableKV}
import org.alephium.protocol.model.{TransactionId, TxOutputRef}
import org.alephium.protocol.vm.nodeindexes.{TxIdTxOutputLocators, TxOutputLocator}
import org.alephium.util.AVector

object TxOutputRefIndexStorage {
  def store(
      storage: MutableKV[TxOutputRef.Key, TxIdTxOutputLocators, Unit],
      outputRef: TxOutputRef.Key,
      txId: TransactionId,
      txOutputLocatorOpt: Option[TxOutputLocator]
  ): IOResult[Unit] = {
    txOutputLocatorOpt match {
      case Some(txOutputLocator) =>
        storage.getOpt(outputRef).flatMap {
          case Some(TxIdTxOutputLocators(txId, txOutputLocators)) =>
            storage.put(outputRef, TxIdTxOutputLocators(txId, txOutputLocators :+ txOutputLocator))
          case None =>
            storage.put(outputRef, TxIdTxOutputLocators(txId, AVector(txOutputLocator)))
        }
      case None =>
        Right(())
    }
  }
}
