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

package org.alephium.protocol.message

import org.scalacheck.Gen

import org.alephium.protocol.Generators
import org.alephium.protocol.config.{CliqueConfig, DiscoveryConfig}
import org.alephium.protocol.model.CliqueId
import org.alephium.util.AVector

trait DiscoveryMessageGenerators extends Generators {
  import DiscoveryMessage._

  lazy val findNodeGen: Gen[FindNode] = for {
    target <- cliqueIdGen
  } yield FindNode(target)

  def pingGen(implicit config: CliqueConfig): Gen[Ping] =
    brokerInfoGen.map(info => Ping.apply(Some(info)))

  def pongGen(implicit config: CliqueConfig): Gen[Pong] =
    brokerInfoGen.map(Pong.apply)

  def neighborsGen(implicit config: CliqueConfig): Gen[Neighbors] =
    for {
      infos <- Gen.listOf(brokerInfoGen)
    } yield Neighbors(AVector.from(infos))

  def messageGen(implicit
      discoveryConfig: DiscoveryConfig,
      cliqueConfig: CliqueConfig
  ): Gen[DiscoveryMessage] =
    for {
      cliqueId <- Gen.const(()).map(_ => CliqueId.generate)
      payload  <- Gen.oneOf[Payload](findNodeGen, pingGen, pongGen, neighborsGen)
    } yield DiscoveryMessage.from(cliqueId, payload)
}
