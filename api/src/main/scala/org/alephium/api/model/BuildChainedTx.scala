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

package org.alephium.api.model

sealed trait BuildChainedTx {
  val value: BuildTxCommon with BuildTxCommon.FromPublicKey
  val `type`: String
}

@upickle.implicits.key("Transfer")
final case class BuildChainedTransferTx(value: BuildTransferTx) extends BuildChainedTx {
  val `type`: String = BuildChainedTransferTx.`type`
}
object BuildChainedTransferTx {
  val `type` = "Transfer"
}

@upickle.implicits.key("DeployContract")
final case class BuildChainedDeployContractTx(value: BuildDeployContractTx) extends BuildChainedTx {
  val `type`: String = BuildChainedDeployContractTx.`type`
}

object BuildChainedDeployContractTx {
  val `type` = "DeployContract"
}

@upickle.implicits.key("ExecuteScript")
final case class BuildChainedExecuteScriptTx(value: BuildExecuteScriptTx) extends BuildChainedTx {
  val `type`: String = BuildChainedExecuteScriptTx.`type`
}

object BuildChainedExecuteScriptTx {
  val `type` = "ExecuteScript"
}
