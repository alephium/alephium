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

package org.alephium.protocol

import java.net.InetSocketAddress

import scala.language.implicitConversions

import org.scalacheck.Gen

import org.alephium.protocol.config.{BrokerConfig, CliqueConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.GasBox
import org.alephium.util.{AVector, NumericHelpers}

trait Generators extends NumericHelpers {
  implicit def gasBox(n: Int): GasBox = GasBox.unsafe(n)

  lazy val portGen: Gen[Int] = Gen.choose(0x401, 65535)

  lazy val hashGen: Gen[Hash] =
    Gen.const(()).map(_ => Hash.generate)

  lazy val blockHashGen: Gen[BlockHash] =
    Gen.const(()).map(_ => BlockHash.generate)

  def groupIndexGen(implicit config: GroupConfig): Gen[GroupIndex] =
    Gen.choose(0, config.groups - 1).map(GroupIndex.unsafe)

  def chainIndexGen(implicit config: GroupConfig): Gen[ChainIndex] =
    for {
      from <- Gen.choose(0, config.groups - 1)
      to   <- Gen.choose(0, config.groups - 1)
    } yield ChainIndex.unsafe(from, to)

  def chainIndexGenRelatedTo(
      broker: BrokerGroupInfo
  )(implicit config: GroupConfig): Gen[ChainIndex] =
    chainIndexGen.retryUntil(_.relateTo(broker))

  def chainIndexGenForBroker(
      broker: BrokerGroupInfo
  )(implicit config: GroupConfig): Gen[ChainIndex] =
    chainIndexGen.retryUntil(index => broker.contains(index.from))

  def chainIndexGenNotRelatedTo(
      broker: BrokerGroupInfo
  )(implicit config: GroupConfig): Gen[ChainIndex] =
    chainIndexGen.retryUntil(!_.relateTo(broker))

  def chainIndexFrom(groupIndex: GroupIndex)(implicit config: GroupConfig): Gen[ChainIndex] =
    Gen.choose(0, config.groups - 1).map(ChainIndex.unsafe(groupIndex.value, _))

  lazy val keypairGen: Gen[(PrivateKey, PublicKey)] =
    Gen.const(()).map(_ => SignatureSchema.secureGeneratePriPub())

  def keypairGen(
      groupIndex: GroupIndex
  )(implicit config: GroupConfig): Gen[(PrivateKey, PublicKey)] =
    Gen.const(()).map(_ => groupIndex.generateKey)

  def publicKeyGen(groupIndex: GroupIndex)(implicit config: GroupConfig): Gen[PublicKey] =
    keypairGen(groupIndex).map(_._2)

  def cliqueIdGen: Gen[CliqueId] =
    keypairGen.map { case (_, pub) =>
      CliqueId(pub)
    }

  def cliqueIdPriKeyGen: Gen[(CliqueId, PrivateKey)] =
    keypairGen.map { case (pri, pub) =>
      (CliqueId(pub), pri)
    }

  def groupNumPerBrokerGen(implicit config: GroupConfig): Gen[Int] =
    Gen.oneOf((1 to config.groups).filter(i => (config.groups % i) equals 0))

  def brokerInfoGen(implicit config: CliqueConfig): Gen[BrokerInfo] =
    for {
      cliqueId <- cliqueIdGen
      brokerId <- Gen.choose(0, config.brokerNum - 1)
      address  <- socketAddressGen
    } yield BrokerInfo.unsafe(cliqueId, brokerId, config.brokerNum, address)

  def brokerInfoGen(cliqueId: CliqueId)(implicit config: CliqueConfig): Gen[BrokerInfo] =
    for {
      brokerId <- Gen.choose(0, config.brokerNum - 1)
      address  <- socketAddressGen
    } yield BrokerInfo.unsafe(cliqueId, brokerId, config.brokerNum, address)

  def cliqueInfoGen(implicit config: GroupConfig): Gen[CliqueInfo] =
    for {
      groupNumPerBroker <- groupNumPerBrokerGen
      peers             <- Gen.listOfN(config.groups / groupNumPerBroker, socketAddressGen)
      cidPri            <- cliqueIdPriKeyGen
    } yield CliqueInfo.unsafe(
      cidPri._1,
      AVector.from(peers.map(Option.apply)),
      AVector.from(peers),
      groupNumPerBroker,
      cidPri._2
    )

  def cliqueInfoGen(groupNumPerBroker: Int)(implicit config: GroupConfig): Gen[CliqueInfo] =
    for {
      peers  <- Gen.listOfN(config.groups / groupNumPerBroker, socketAddressGen)
      cidPri <- cliqueIdPriKeyGen
    } yield CliqueInfo.unsafe(
      cidPri._1,
      AVector.from(peers.map(Option.apply)),
      AVector.from(peers),
      groupNumPerBroker,
      cidPri._2
    )

  def peerInfoGen(implicit config: BrokerConfig): Gen[BrokerInfo] =
    for {
      cid               <- cliqueIdGen
      groupNumPerBroker <- groupNumPerBrokerGen
      brokerId          <- Gen.choose(0, (config.groups / groupNumPerBroker) - 1)
      address           <- socketAddressGen
    } yield BrokerInfo.unsafe(cid, brokerId, groupNumPerBroker, address)

  lazy val socketAddressGen: Gen[InetSocketAddress] =
    for {
      ip0  <- Gen.choose(0, 255)
      ip1  <- Gen.choose(0, 255)
      ip2  <- Gen.choose(0, 255)
      ip3  <- Gen.choose(0, 255)
      port <- portGen
    } yield new InetSocketAddress(s"$ip0.$ip1.$ip2.$ip3", port)

  lazy val versionGen: Gen[(String, ReleaseVersion)] = {
    val positiveInt = Gen.choose(0, Int.MaxValue)
    for {
      major    <- positiveInt
      minor    <- positiveInt
      patch    <- positiveInt
      commitId <- Gen.option(Gen.hexStr)
    } yield (
      s"$major.$minor.$patch${commitId.map(id => s"+$id").getOrElse("")}",
      ReleaseVersion(major, minor, patch)
    )
  }
}

trait DefaultGenerators extends Generators {
  implicit def config: GroupConfig =
    new GroupConfig {
      override def groups: Int = 3
    }
}

object Generators extends Generators
