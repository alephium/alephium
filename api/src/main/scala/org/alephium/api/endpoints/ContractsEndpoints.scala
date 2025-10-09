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

package org.alephium.api.endpoints

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.Endpoints._
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.Hash
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.protocol.vm.StatefulContract

trait ContractsEndpoints extends BaseEndpoints {

  private val contractsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("contracts")
      .tag("Contracts")

  private val contractsUnsignedTxEndpoint: BaseEndpoint[Unit, Unit] =
    contractsEndpoint.in("unsigned-tx")

  val compileScript: BaseEndpoint[Compile.Script, CompileScriptResult] =
    contractsEndpoint.post
      .in("compile-script")
      .in(jsonBody[Compile.Script])
      .out(jsonBody[CompileScriptResult])
      .summary("Compile a script")

  val buildExecuteScriptTx: BaseEndpoint[BuildExecuteScriptTx, BuildExecuteScriptTxResult] =
    contractsUnsignedTxEndpoint.post
      .in("execute-script")
      .in(jsonBody[BuildExecuteScriptTx])
      .out(jsonBody[BuildExecuteScriptTxResult])
      .summary("Build an unsigned script")

  val compileContract: BaseEndpoint[Compile.Contract, CompileContractResult] =
    contractsEndpoint.post
      .in("compile-contract")
      .in(jsonBody[Compile.Contract])
      .out(jsonBody[CompileContractResult])
      .summary("Compile a smart contract")

  val compileProject: BaseEndpoint[Compile.Project, CompileProjectResult] =
    contractsEndpoint.post
      .in("compile-project")
      .in(jsonBody[Compile.Project])
      .out(jsonBody[CompileProjectResult])
      .summary("Compile a project")

  val buildDeployContractTx: BaseEndpoint[BuildDeployContractTx, BuildDeployContractTxResult] =
    contractsUnsignedTxEndpoint.post
      .in("deploy-contract")
      .in(jsonBody[BuildDeployContractTx])
      .out(jsonBody[BuildDeployContractTxResult])
      .summary("Build an unsigned contract")

  lazy val contractState: BaseEndpoint[Address.Contract, ContractState] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("state")
      .out(jsonBody[ContractState])
      .summary("Get contract state")

  lazy val contractCode: BaseEndpoint[Hash, StatefulContract] =
    contractsEndpoint.get
      .in(path[Hash]("codeHash"))
      .in("code")
      .out(jsonBody[StatefulContract])
      .summary("Get contract code by code hash")

  lazy val testContract: BaseEndpoint[TestContract, TestContractResult] =
    contractsEndpoint.post
      .in("test-contract")
      .in(jsonBody[TestContract])
      .out(jsonBody[TestContractResult])
      .summary("Test contract")

  lazy val callContract: BaseEndpoint[CallContract, CallContractResult] =
    contractsEndpoint.post
      .in("call-contract")
      .in(jsonBody[CallContract])
      .out(jsonBody[CallContractResult])
      .summary("Call contract")

  lazy val multiCallContract: BaseEndpoint[MultipleCallContract, MultipleCallContractResult] =
    contractsEndpoint.post
      .in("multicall-contract")
      .in(jsonBody[MultipleCallContract])
      .out(jsonBody[MultipleCallContractResult])
      .summary("Multiple call contract")

  lazy val parentContract: BaseEndpoint[Address.Contract, ContractParent] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("parent")
      .out(jsonBody[ContractParent])
      .summary("Get parent contract address")

  lazy val subContracts: BaseEndpoint[(Address.Contract, CounterRange), SubContracts] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("sub-contracts")
      .in(counterQuery)
      .out(jsonBody[SubContracts])
      .summary("Get sub-contract addresses")

  val subContractsCurrentCount: BaseEndpoint[Address.Contract, Int] =
    contractsEndpoint.get
      .in(path[Address.Contract]("address"))
      .in("sub-contracts")
      .in("current-count")
      .out(jsonBody[Int])
      .summary("Get current value of the sub-contracts counter for a contract")

  lazy val callTxScript: BaseEndpoint[CallTxScript, CallTxScriptResult] =
    contractsEndpoint.post
      .in("call-tx-script")
      .in(jsonBody[CallTxScript])
      .out(jsonBody[CallTxScriptResult])
      .summary("Call TxScript")
}
