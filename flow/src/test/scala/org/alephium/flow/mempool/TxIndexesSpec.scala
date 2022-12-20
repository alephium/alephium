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

package org.alephium.flow.mempool

import org.alephium.flow.AlephiumFlowSpec
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumFixture, LockFixture}

class TxIndexesSpec
    extends AlephiumFlowSpec
    with TxIndexesSpec.Fixture
    with LockFixture
    with NoIndexModelGeneratorsLike {
  def emptyTxIndexes = TxIndexes.emptyMemPool(GroupIndex.unsafe(0))

  it should "add txs" in {
    val tx      = transactionGen().sample.get
    val indexes = emptyTxIndexes
    indexes.add(tx.toTemplate, _ => ())
    checkTx(indexes, tx.toTemplate)
  }

  it should "be idempotent for adding" in {
    val tx       = transactionGen().sample.get
    val indexes0 = emptyTxIndexes
    indexes0.add(tx.toTemplate, _ => ())

    val indexes1 = emptyTxIndexes
    indexes1.add(tx.toTemplate, _ => ())
    indexes1.add(tx.toTemplate, _ => ())

    indexes0 is indexes1
  }

  it should "remove tx" in {
    val tx      = transactionGen().sample.get
    val indexes = emptyTxIndexes
    indexes.add(tx.toTemplate, _ => ())
    indexes.remove(tx.toTemplate)
    indexes is emptyTxIndexes

    // check for idempotent
    indexes.remove(tx.toTemplate)
    indexes is emptyTxIndexes
  }
}

object TxIndexesSpec {
  trait Fixture extends AlephiumFixture {
    def checkTx(indexes: TxIndexes, tx: TransactionTemplate)(implicit
        groupConfig: GroupConfig
    ): Unit = {
      tx.unsigned.inputs.foreach { input =>
        indexes.inputIndex.contains(input.outputRef) is true
      }
      tx.unsigned.fixedOutputs.foreachWithIndex { case (output, index) =>
        val outputRef = AssetOutputRef.from(output, TxOutputRef.key(tx.id, index))
        if (output.toGroup equals indexes.mainGroup) {
          indexes.outputIndex.contains(outputRef) is true
          indexes.addressIndex(output.lockupScript).contains(outputRef) is true
        } else {
          indexes.outputIndex.contains(outputRef) is false
          indexes.addressIndex.get(output.lockupScript) match {
            case Some(outputs) => outputs.contains(outputRef) is false
            case None          => ()
          }
        }
      }
    }
  }
}
