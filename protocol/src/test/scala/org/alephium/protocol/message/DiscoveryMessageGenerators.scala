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

import org.alephium.protocol.{Generators, PublicKey}
import org.alephium.protocol.config.CliqueConfig
import org.alephium.protocol.model.CliqueId
import org.alephium.util.AVector

trait DiscoveryMessageGenerators extends Generators {
  import DiscoveryMessage._

  def discoveryPublicKey: PublicKey
  private def cliqueId = CliqueId(discoveryPublicKey)

  lazy val idGen: Gen[Id] = Gen.const(()).map(_ => Id.random())

  lazy val findNodeGen: Gen[FindNode] = for {
    target <- cliqueIdGen
  } yield FindNode(target)

  def pingGen(implicit config: CliqueConfig): Gen[Ping] = for {
    id     <- idGen
    broker <- brokerInfoGen(cliqueId)
  } yield Ping(id, Some(broker))

  def pongGen(implicit config: CliqueConfig): Gen[Pong] = for {
    id     <- idGen
    broker <- brokerInfoGen(cliqueId)
  } yield Pong(id, broker)

  def neighborsGen(implicit config: CliqueConfig): Gen[Neighbors] =
    for {
      infos <- Gen.listOf(brokerInfoGen)
    } yield Neighbors(AVector.from(infos))

  def messageGen(implicit
      cliqueConfig: CliqueConfig
  ): Gen[DiscoveryMessage] =
    for {
      payload <- Gen.oneOf[Payload](findNodeGen, pingGen, pongGen, neighborsGen)
    } yield DiscoveryMessage.from(payload)
}
