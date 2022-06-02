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

package org.alephium.protocol.config

import akka.util.ByteString

import org.alephium.protocol.model.NetworkId
import org.alephium.util.TimeStamp

trait NetworkConfigFixture { self =>
  def networkId: NetworkId
  def lemanHardForkTimestamp: TimeStamp = TimeStamp.zero

  implicit lazy val networkConfig: NetworkConfig = new NetworkConfig {
    val networkId: NetworkId       = self.networkId
    val noPreMineProof: ByteString = ByteString.empty
    val lemanHardForkTimestamp: TimeStamp =
      self.lemanHardForkTimestamp // enabled by default for all tests
  }
}

object NetworkConfigFixture {
  trait Default extends NetworkConfigFixture {
    def networkId: NetworkId = NetworkId.AlephiumDevNet
  }

  val PreLeman = new NetworkConfig {
    override def networkId: NetworkId              = NetworkId.AlephiumMainNet
    override def noPreMineProof: ByteString        = ByteString.empty
    override def lemanHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
  }

  val Leman = new NetworkConfig {
    override def networkId: NetworkId              = NetworkId.AlephiumMainNet
    override def noPreMineProof: ByteString        = ByteString.empty
    override def lemanHardForkTimestamp: TimeStamp = TimeStamp.unsafe(0)
  }
}
