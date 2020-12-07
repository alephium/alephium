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

package org.alephium.flow.setting

import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq

import pureconfig.ConfigSource
import pureconfig.generic.auto._

import org.alephium.protocol.model.NetworkType
import org.alephium.util.{AlephiumSpec, Duration}

class AlephiumConfigSpec extends AlephiumSpec {
  it should "load alephium config" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", "13"),
      ("alephium.consensus.block-target-time", "11 seconds")
    )

    config.broker.groups is 13
    config.network.networkType is NetworkType.Devnet
    config.consensus.blockTargetTime is Duration.ofSecondsUnsafe(11)
  }

  it should "load bootstrap config" in {
    import PureConfigUtils._

    case class Bootstrap(addresses: ArraySeq[InetSocketAddress])

    val expected = Bootstrap(
      ArraySeq(new InetSocketAddress("localhost", 1234), new InetSocketAddress("localhost", 4321)))

    ConfigSource
      .string("""{ addresses = ["localhost:1234", "localhost:4321"] }""")
      .load[Bootstrap] isE expected

    ConfigSource
      .string("""{ addresses = "localhost:1234,localhost:4321" }""")
      .load[Bootstrap] isE expected

    ConfigSource
      .string("""{ addresses = "" }""")
      .load[Bootstrap] isE Bootstrap(ArraySeq.empty)
  }
}
