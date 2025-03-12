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

import scala.collection.immutable.ArraySeq
import scala.util.Random

import akka.util.ByteString

import org.alephium.protocol.model.{HardFork, NetworkId}
import org.alephium.util.TimeStamp

trait NetworkConfigFixture { self =>
  def networkId: NetworkId
  def lemanHardForkTimestamp: TimeStamp
  def rhoneHardForkTimestamp: TimeStamp
  def danubeHardForkTimestamp: TimeStamp

  implicit lazy val networkConfig: NetworkConfig = new NetworkConfig {
    val networkId: NetworkId       = self.networkId
    val noPreMineProof: ByteString = ByteString.empty
    val lemanHardForkTimestamp: TimeStamp =
      self.lemanHardForkTimestamp // enabled by default for all tests
    val rhoneHardForkTimestamp: TimeStamp  = self.rhoneHardForkTimestamp
    val danubeHardForkTimestamp: TimeStamp = self.danubeHardForkTimestamp
  }
}

object NetworkConfigFixture {
  lazy val All = ArraySeq(Genesis, Leman, Rhone, Danube)

  def getNetworkConfig(hardFork: HardFork): NetworkConfig = {
    val config = hardFork match {
      case HardFork.Mainnet => Genesis
      case HardFork.Leman   => Leman
      case HardFork.Rhone   => Rhone
      case HardFork.Danube  => Danube
      case _                => ???
    }
    assume(config.getHardFork(TimeStamp.now()) == hardFork)
    config
  }

  def getNetworkConfigSince(hardFork: HardFork): NetworkConfig = {
    assume(hardFork != HardFork.Mainnet)
    val config = hardFork match {
      case HardFork.Leman  => SinceLeman
      case HardFork.Rhone  => SinceRhone
      case HardFork.Danube => SinceDanube
      case _               => ???
    }
    val hardForks = HardFork.All.drop(HardFork.All.indexOf(hardFork))
    assume(hardForks.contains(config.getHardFork(TimeStamp.now())))
    config
  }

  def getNetworkConfigBefore(hardFork: HardFork): NetworkConfig = {
    assume(hardFork != HardFork.Mainnet && hardFork != HardFork.Leman)
    val config = hardFork match {
      case HardFork.Rhone  => PreRhone
      case HardFork.Danube => PreDanube
      case _               => ???
    }
    val hardForks = HardFork.All.take(HardFork.All.indexOf(hardFork))
    assume(hardForks.contains(config.getHardFork(TimeStamp.now())))
    config
  }

  trait Default extends NetworkConfigFixture {
    def networkId: NetworkId               = NetworkId.AlephiumDevNet
    def lemanHardForkTimestamp: TimeStamp  = TimeStamp.zero
    def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.zero
    def danubeHardForkTimestamp: TimeStamp = TimeStamp.zero
  }

  trait GenesisT extends NetworkConfigFixture {
    override def networkId: NetworkId               = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(Long.MaxValue)
    override def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(Long.MaxValue)
    override def danubeHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
  }
  val Genesis = new GenesisT {}.networkConfig

  trait LemanT extends NetworkConfigFixture {
    override def networkId: NetworkId               = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(0)
    override def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(Long.MaxValue)
    override def danubeHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
  }
  val Leman = new LemanT {}.networkConfig

  trait RhoneT extends NetworkConfigFixture {
    override def networkId: NetworkId               = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(0)
    override def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(0)
    override def danubeHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
  }
  val Rhone = new RhoneT {}.networkConfig

  trait DanubeT extends NetworkConfigFixture {
    override def networkId: NetworkId               = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(0)
    override def rhoneHardForkTimestamp: TimeStamp  = TimeStamp.unsafe(0)
    override def danubeHardForkTimestamp: TimeStamp = TimeStamp.unsafe(0)
  }
  val Danube = new DanubeT {}.networkConfig

  lazy val sinceLemanForks = All.drop(1)
  trait SinceLemanT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = sinceLemanForks(Random.nextInt(sinceLemanForks.length))
    override def lemanHardForkTimestamp: TimeStamp  = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp  = fork.rhoneHardForkTimestamp
    override def danubeHardForkTimestamp: TimeStamp = fork.danubeHardForkTimestamp
  }
  val SinceLeman = new SinceLemanT {}.networkConfig

  lazy val sinceRhoneForks = All.drop(2)
  trait SinceRhoneT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = sinceRhoneForks(Random.nextInt(sinceRhoneForks.length))
    override def lemanHardForkTimestamp: TimeStamp  = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp  = fork.rhoneHardForkTimestamp
    override def danubeHardForkTimestamp: TimeStamp = fork.danubeHardForkTimestamp
  }
  val SinceRhone = new SinceRhoneT {}.networkConfig

  lazy val preRhoneForks = All.take(2)
  trait PreRhoneT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = preRhoneForks(Random.nextInt(preRhoneForks.length))
    override def lemanHardForkTimestamp: TimeStamp  = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp  = fork.rhoneHardForkTimestamp
    override def danubeHardForkTimestamp: TimeStamp = fork.danubeHardForkTimestamp
  }
  val PreRhone = new PreRhoneT {}.networkConfig

  lazy val sinceDanubeForks = All.takeRight(1)
  trait SinceDanubeT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = sinceDanubeForks(Random.nextInt(sinceDanubeForks.length))
    override def lemanHardForkTimestamp: TimeStamp  = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp  = fork.rhoneHardForkTimestamp
    override def danubeHardForkTimestamp: TimeStamp = fork.danubeHardForkTimestamp
  }
  val SinceDanube = new SinceDanubeT {}.networkConfig

  lazy val preDanubeForks = All.dropRight(1)
  trait PreDanubeT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = preDanubeForks(Random.nextInt(preDanubeForks.length))
    override def lemanHardForkTimestamp: TimeStamp  = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp  = fork.rhoneHardForkTimestamp
    override def danubeHardForkTimestamp: TimeStamp = fork.danubeHardForkTimestamp
  }
  val PreDanube = new PreDanubeT {}.networkConfig
}
