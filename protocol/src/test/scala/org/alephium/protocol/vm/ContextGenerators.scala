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

import org.alephium.protocol.Signature
import org.alephium.protocol.config.{GroupConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, TimeStamp}

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def genBlockEnv()(implicit networkConfig: NetworkConfig): BlockEnv = {
    val groupIndex = groupIndexGen.sample.get
    val chainIndex = ChainIndex(groupIndex, groupIndex)
    BlockEnv(
      chainIndex,
      NetworkId.AlephiumDevNet,
      TimeStamp.now(),
      Target.Max,
      Some(BlockHash.generate)
    )
  }

  def genTxEnv(scriptOpt: Option[StatefulScript] = None, signatures: AVector[Signature]): TxEnv = {
    val (tx, prevOutputs) = {
      val (tx, prevOutputs) = transactionGenWithPreOutputs().sample.get
      tx.copy(unsigned = tx.unsigned.copy(scriptOpt = scriptOpt)) -> prevOutputs
    }
    TxEnv.dryrun(
      tx,
      prevOutputs.map(_.referredOutput),
      Stack.popOnly(signatures)
    )
  }

  def genStatelessContext(
      gasLimit: GasBox = minimalGas,
      signatures: AVector[Signature] = AVector.empty,
      blockEnv: Option[BlockEnv] = None,
      txEnv: Option[TxEnv] = None
  )(implicit networkConfig: NetworkConfig): StatelessContext = {
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
      scriptOpt: Option[StatefulScript] = None,
      gasLimit: GasBox = minimalGas,
      signatures: AVector[Signature] = AVector.empty
  )(implicit networkConfig: NetworkConfig): StatefulContext = {
    val txEnv = genTxEnv(scriptOpt, signatures)
    StatefulContext(
      genBlockEnv(),
      txEnv,
      cachedWorldState.staging(),
      gasLimit
    )(networkConfig, LogConfig.allEnabled(), groupConfig)
  }

  def prepareStatefulScript(
      script: StatefulScript,
      gasLimit: GasBox = minimalGas
  )(implicit networkConfig: NetworkConfig): (ScriptObj[StatefulContext], StatefulContext) = {
    val obj     = script.toObject
    val context = genStatefulContext(scriptOpt = Some(script), gasLimit = gasLimit)
    obj -> context
  }

  def prepareContract(
      contract: StatefulContract,
      immFields: AVector[Val],
      mutFields: AVector[Val],
      gasLimit: GasBox = GasBox.unsafe(100000),
      contractOutputOpt: Option[(ContractId, ContractOutput, ContractOutputRef)] = None,
      txEnvOpt: Option[TxEnv] = None
  )(implicit _networkConfig: NetworkConfig): (StatefulContractObject, StatefulContext) = {
    val groupIndex = GroupIndex.unsafe(0)
    val (contractId, contractOutput, contractOutputRef) = contractOutputOpt.getOrElse {
      val ci  = ContractId.random
      val co  = contractOutputGen(scriptGen = p2cLockupGen(groupIndex)).sample.get
      val cor = ContractOutputRef.from(TransactionId.generate, co, 0)
      (ci, co, cor)
    }
    val halfDecoded       = contract.toHalfDecoded()
    val transactionEnv    = txEnvOpt.getOrElse(genTxEnv(None, AVector.empty))
    val generatedBlockEnv = genBlockEnv()(_networkConfig)

    cachedWorldState.createContractUnsafe(
      contractId,
      halfDecoded,
      immFields,
      mutFields,
      contractOutputRef,
      contractOutput,
      _networkConfig.getHardFork(TimeStamp.now()).isLemanEnabled(),
      transactionEnv.txId,
      None
    ) isE ()

    val obj          = halfDecoded.toObjectUnsafeTestOnly(contractId, immFields, mutFields)
    val _groupConfig = groupConfig
    val context = new StatefulContext {
      val worldState: WorldState.Staging               = cachedWorldState.staging()
      val networkConfig: NetworkConfig                 = _networkConfig
      val groupConfig: GroupConfig                     = _groupConfig
      val outputBalances: MutBalances                  = MutBalances.empty
      def nextOutputIndex: Int                         = 0
      val blockEnv: BlockEnv                           = generatedBlockEnv
      val txEnv: TxEnv                                 = transactionEnv
      def getInitialBalances(): ExeResult[MutBalances] = failed(ExpectNonPayableMethod)
      def logConfig: LogConfig                         = LogConfig.allEnabled()
      def nodeIndexesConfig: NodeIndexesConfig         = NodeIndexesConfig(true, true)
      var gasRemaining: GasBox                         = gasLimit
    }
    obj -> context
  }
}
