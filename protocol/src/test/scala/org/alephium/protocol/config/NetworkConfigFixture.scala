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

import org.alephium.protocol.model.NetworkId
import org.alephium.util.TimeStamp

trait NetworkConfigFixture { self =>
  def networkId: NetworkId
  def lemanHardForkTimestamp: TimeStamp
  def rhoneHardForkTimestamp: TimeStamp

  implicit lazy val networkConfig: NetworkConfig = new NetworkConfig {
    val networkId: NetworkId       = self.networkId
    val noPreMineProof: ByteString = ByteString.empty
    val lemanHardForkTimestamp: TimeStamp =
      self.lemanHardForkTimestamp // enabled by default for all tests
    val rhoneHardForkTimestamp: TimeStamp = self.rhoneHardForkTimestamp
  }
}

object NetworkConfigFixture {
  lazy val All = ArraySeq(Genesis, Leman, Rhone)

  trait Default extends NetworkConfigFixture {
    def networkId: NetworkId              = NetworkId.AlephiumDevNet
    def lemanHardForkTimestamp: TimeStamp = TimeStamp.zero
    def rhoneHardForkTimestamp: TimeStamp = TimeStamp.zero
  }

  trait GenesisT extends NetworkConfigFixture {
    override def networkId: NetworkId              = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
    override def rhoneHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
  }
  val Genesis = new GenesisT {}.networkConfig

  trait LemanT extends NetworkConfigFixture {
    override def networkId: NetworkId              = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp = TimeStamp.unsafe(0)
    override def rhoneHardForkTimestamp: TimeStamp = TimeStamp.unsafe(Long.MaxValue)
  }
  val Leman = new LemanT {}.networkConfig

  trait RhoneT extends NetworkConfigFixture {
    override def networkId: NetworkId              = NetworkId.AlephiumMainNet
    override def lemanHardForkTimestamp: TimeStamp = TimeStamp.unsafe(0)
    override def rhoneHardForkTimestamp: TimeStamp = TimeStamp.unsafe(0)
  }
  val Rhone = new RhoneT {}.networkConfig

  lazy val sinceLemanForks = All.drop(1)
  trait SinceLemanT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = sinceLemanForks(Random.nextInt(sinceLemanForks.length))
    override def lemanHardForkTimestamp: TimeStamp = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp = fork.rhoneHardForkTimestamp
  }
  val SinceLeman = new SinceLemanT {}.networkConfig

  lazy val sinceRhoneForks = All.takeRight(1)
  trait SinceRhoneT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = sinceRhoneForks(Random.nextInt(sinceRhoneForks.length))
    override def lemanHardForkTimestamp: TimeStamp = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp = fork.rhoneHardForkTimestamp
  }
  val SinceRhone = new SinceRhoneT {}.networkConfig

  lazy val preRhoneForks = All.dropRight(1)
  trait PreRhoneT extends NetworkConfigFixture {
    override def networkId: NetworkId = NetworkId.AlephiumMainNet
    private lazy val fork             = preRhoneForks(Random.nextInt(preRhoneForks.length))
    override def lemanHardForkTimestamp: TimeStamp = fork.lemanHardForkTimestamp
    override def rhoneHardForkTimestamp: TimeStamp = fork.rhoneHardForkTimestamp
  }
  val PreRhone = new PreRhoneT {}.networkConfig
}
