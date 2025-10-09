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
import org.alephium.api.alphPlainTextBody
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}

trait UtilsEndpoints extends BaseEndpoints {
  private val utilsEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("utils")
      .tag("Utils")

  val verifySignature: BaseEndpoint[VerifySignature, Boolean] =
    utilsEndpoint.post
      .in("verify-signature")
      .in(jsonBody[VerifySignature])
      .out(jsonBody[Boolean])
      .summary("Verify the SecP256K1 signature of some data")

  val checkHashIndexing: BaseEndpoint[Unit, Unit] =
    utilsEndpoint.put
      .in("check-hash-indexing")
      .summary("Check and repair the indexing of block hashes")

  val targetToHashrate: BaseEndpoint[TargetToHashrate, TargetToHashrate.Result] =
    utilsEndpoint.post
      .in("target-to-hashrate")
      .in(jsonBody[TargetToHashrate])
      .out(jsonBody[TargetToHashrate.Result])
      .summary("Convert a target to hashrate")

  val exportBlocks: BaseEndpoint[ExportFile, Unit] =
    baseEndpoint.post
      .in("export-blocks")
      .in(jsonBody[ExportFile])
      .summary("Exports all the blocks")

  val metrics: BaseEndpointWithoutApi[Unit, String] =
    baseEndpointWithoutApiKey.get
      .in("metrics")
      .out(alphPlainTextBody)
      .summary("Exports all prometheus metrics")
}
