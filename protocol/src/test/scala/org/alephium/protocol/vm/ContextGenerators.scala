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

import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp}

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def genBlockEnv(): BlockEnv = {
    BlockEnv(NetworkId.AlephiumDevNet, TimeStamp.now(), Target.onePhPerBlock)
  }

  def genTxEnv(scriptOpt: Option[StatefulScript] = None, signatures: AVector[Signature]): TxEnv = {
    val (tx, prevOutputs) = {
      val (tx, prevOutputs) = transactionGenWithPreOutputs().sample.get
      tx.copy(unsigned = tx.unsigned.copy(scriptOpt = scriptOpt)) -> prevOutputs
    }
    TxEnv(tx, prevOutputs.map(_.referredOutput), Stack.popOnly(signatures))
  }

  def genStatelessContext(
      gasLimit: GasBox = minimalGas,
      signatures: AVector[Signature] = AVector.empty,
      blockEnv: Option[BlockEnv] = None,
      txEnv: Option[TxEnv] = None
  ): StatelessContext = {
    StatelessContext.apply(
      blockEnv.getOrElse(genBlockEnv()),
      txEnv.getOrElse(genTxEnv(signatures = signatures)),
      gasLimit
    )
  }

  def prepareStatelessScript(
      script: StatelessScript,
      gasLimit: GasBox = minimalGas,
      signatures: AVector[Signature] = AVector.empty
  ): (ScriptObj[StatelessContext], StatelessContext) = {
    val obj     = script.toObject
    val context = genStatelessContext(gasLimit, signatures)
    obj -> context
  }

  def genStatefulContext(
      scriptOpt: Option[StatefulScript],
      gasLimit: GasBox = minimalGas,
      signatures: AVector[Signature] = AVector.empty
  ): StatefulContext = {
    val txEnv = genTxEnv(scriptOpt, signatures)
    StatefulContext
      .build(
        genBlockEnv(),
        txEnv.tx,
        gasLimit,
        cachedWorldState,
        Some(txEnv.prevOutputs)
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
      gasLimit: GasBox = GasBox.unsafe(100000)
  ): (StatefulContractObject, StatefulContext) = {
    val groupIndex        = GroupIndex.unsafe(0)
    val contractOutputRef = contractOutputRefGen(groupIndex).sample.get
    val p2cLockup         = p2cLockupGen(groupIndex)
    val contractOutput    = contractOutputGen(scriptGen = p2cLockup).sample.get
    val halfDecoded       = contract.toHalfDecoded()

    cachedWorldState.createContractUnsafe(
      halfDecoded,
      Hash.zero,
      fields,
      contractOutputRef,
      contractOutput
    ) isE ()

    val obj = halfDecoded.toObjectUnsafe(contractOutputRef.key, Hash.zero, fields)
    val context = new StatefulContext {
      val worldState: WorldState.Staging            = cachedWorldState.staging()
      def outputBalances: Balances                  = ???
      def nextOutputIndex: Int                      = ???
      def blockEnv: BlockEnv                        = ???
      def txEnv: TxEnv                              = ???
      def getInitialBalances(): ExeResult[Balances] = failed(ExpectNonPayableMethod)
      var gasRemaining: GasBox                      = gasLimit
    }
    obj -> context
  }
}
