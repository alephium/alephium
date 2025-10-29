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

trait MultisigEndpoints extends BaseEndpoints {
  private val multisigEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("multisig")
      .tag("Multi-signature")

  val buildMultisigAddress: BaseEndpoint[BuildMultisigAddress, BuildMultisigAddressResult] =
    multisigEndpoint.post
      .in("address")
      .in(jsonBodyWithAlph[BuildMultisigAddress])
      .out(jsonBody[BuildMultisigAddressResult])
      .summary("Create the multisig address and unlock script")

  val buildMultisig: BaseEndpoint[BuildMultisig, BuildTransferTxResult] =
    multisigEndpoint.post
      .in("build")
      .in(jsonBody[BuildMultisig])
      .out(jsonBody[BuildTransferTxResult])
      .summary("Build a multisig unsigned transaction")

  val buildSweepMultisig: BaseEndpoint[BuildSweepMultisig, BuildSweepAddressTransactionsResult] =
    multisigEndpoint.post
      .in("sweep")
      .in(jsonBody[BuildSweepMultisig])
      .out(jsonBody[BuildSweepAddressTransactionsResult])
      .summary(
        "Sweep all unlocked ALPH and token balances of a multisig address to another address"
      )

  val submitMultisigTransaction: BaseEndpoint[SubmitMultisig, SubmitTxResult] =
    multisigEndpoint.post
      .in("submit")
      .in(jsonBody[SubmitMultisig])
      .out(jsonBody[SubmitTxResult])
      .summary("Submit a multi-signed transaction")
}
