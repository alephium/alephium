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

package org.alephium.protocol.vm

import org.alephium.protocol
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp}

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def genStatelessContext(gasLimit: GasBox = minimalGas): StatelessContext =
    StatelessContext.apply(
      BlockEnv(ChainId.AlephiumDevNet, TimeStamp.now(), Target.onePhPerBlock),
      Hash.zero,
      gasLimit,
      Stack.ofCapacity[protocol.Signature](0)
    )

  def prepareStatelessScript(
      script: StatelessScript,
      gasLimit: GasBox = minimalGas
  ): (ScriptObj[StatelessContext], StatelessContext) = {
    val obj     = script.toObject
    val context = genStatelessContext(gasLimit)
    obj -> context
  }

  def genStatefulContext(
      scriptOpt: Option[StatefulScript],
      gasLimit: GasBox = minimalGas
  ): StatefulContext = {
    val (tx, preOutputs) = {
      val (tx, preOutputs) = transactionGenWithPreOutputs().sample.get
      tx.copy(unsigned = tx.unsigned.copy(scriptOpt = scriptOpt)) -> preOutputs
    }
    StatefulContext
      .build(
        BlockEnv(ChainId.AlephiumDevNet, TimeStamp.now(), Target.onePhPerBlock),
        tx,
        gasLimit,
        cachedWorldState,
        Some(preOutputs.map(_.referredOutput))
      )
      .rightValue
  }

  def prepareStatefulScript(
      script: StatefulScript,
      gasLimit: GasBox = minimalGas
  ): (ScriptObj[StatefulContext], StatefulContext) = {
    val obj     = script.toObject
    val context = genStatefulContext(scriptOpt = Some(script), gasLimit = gasLimit)
    obj -> context
  }

  def prepareContract(
      contract: StatefulContract,
      fields: AVector[Val],
      gasLimit: GasBox = minimalGas
  ): (StatefulContractObject, StatefulContext) = {
    val groupIndex        = GroupIndex.unsafe(0)
    val contractOutputRef = contractOutputRefGen(groupIndex).sample.get
    val contractOutput    = contractOutputGen().sample.get
    val halfDecoded       = contract.toHalfDecoded()

    cachedWorldState.createContractUnsafe(
      halfDecoded,
      fields,
      contractOutputRef,
      contractOutput
    ) isE ()

    val obj = halfDecoded.toObject(contractOutputRef.key, fields)
    val context = new StatefulContext {
      override val worldState: WorldState.Staging            = cachedWorldState.staging()
      override def outputBalances: Balances                  = ???
      override def nextOutputIndex: Int                      = ???
      override def blockEnv: BlockEnv                        = ???
      override def txId: Hash                                = Hash.zero
      override def signatures: Stack[protocol.Signature]     = Stack.ofCapacity(0)
      override def getInitialBalances(): ExeResult[Balances] = failed(ExpectNonPayableMethod)
      override var gasRemaining: GasBox                      = gasLimit
    }
    obj -> context
  }
}
