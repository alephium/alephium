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
import scala.jdk.CollectionConverters._

import com.typesafe.config.ConfigValueFactory
import pureconfig.ConfigSource
import pureconfig.error._
import pureconfig.generic.auto._

import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.util.{AlephiumSpec, AVector, Duration}

class AlephiumConfigSpec extends AlephiumSpec {
  it should "load alephium config" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", "13"),
      ("alephium.consensus.block-target-time", "11 seconds")
    )

    config.broker.groups is 13
    config.network.networkType is NetworkType.Devnet
    config.consensus.blockTargetTime is Duration.ofSecondsUnsafe(11)
    config.network.connectionBufferCapacityInByte is 100000000L
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

  it should "load miner's addresses" in new AlephiumConfigFixture {
    val minerAddresses = AVector(
      "D19zzHckZmX9Sjs6yERD15JBLa7HhVXfdrUAMRmLgKFpcr",
      "D15kHgMQX6ZMH3prxEFcFDkFBv4B7dcffCwVSRCr8nUe7N"
    )

    override val configValues: Map[String, Any] = Map(
      ("alephium.miner-addresses", ConfigValueFactory.fromIterable(minerAddresses.toSeq.asJava))
    )

    config.minerAddresses is minerAddresses.map(str =>
      Address.fromBase58(str, NetworkType.Devnet).get.lockupScript)
  }

  it should "fail to load if miner's addresses are wrong" in new AlephiumConfigFixture {
    val minerAddresses = AVector(
      "T149bUQbTo6tHa35U3QC1tsAkEDaryyQGJD2S8eomYfcZx",
      "T1D9PBcRXK5uzrNYokNMB7oh6JpW86sZajJ5gD845cshED"
    )

    override val configValues: Map[String, Any] = Map(
      ("alephium.miner-addresses", ConfigValueFactory.fromIterable(minerAddresses.toSeq.asJava))
    )

    AlephiumConfig.load(newConfig.getConfig("alephium")).leftValue is a[ConfigReaderFailures]
  }

  it should "fail to load if `mainnet` is set but no miner's addresses" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.network-type", "mainnet")
    )

    AlephiumConfig.load(newConfig.getConfig("alephium")).leftValue is a[ConfigReaderFailures]
  }

  it should "generate miner's addresses if not set and network is `testnet`" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.network-type", "testnet")
    )

    config.network.networkType is NetworkType.Testnet
    config.minerAddresses.length is config.broker.groups
    config.minerAddresses.foreachWithIndex { (miner, i) =>
      miner.groupIndex.value is i
    }
  }

  it should "generate miner's addresses if not set and network is `devnet`" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.network.network-type", "devnet")
    )

    config.network.networkType is NetworkType.Devnet
    config.minerAddresses.length is config.broker.groups
    config.minerAddresses.foreachWithIndex { (miner, i) =>
      miner.groupIndex.value is i
    }
  }
}
