package org.alephium.protocol

import java.net.InetSocketAddress

import org.scalacheck.Gen

import org.alephium.protocol.{PrivateKey, PublicKey}
import org.alephium.protocol.config.{CliqueConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, NumericHelpers}

trait Generators extends NumericHelpers {

  lazy val portGen: Gen[Int] = Gen.choose(0x401, 65535)

  lazy val hashGen: Gen[Hash] =
    Gen.const(()).map(_ => Hash.generate)

  def groupIndexGen(implicit config: GroupConfig): Gen[GroupIndex] =
    Gen.choose(0, config.groups - 1).map(GroupIndex.unsafe)

  def chainIndexGen(implicit config: GroupConfig): Gen[ChainIndex] =
    for {
      from <- Gen.choose(0, config.groups - 1)
      to   <- Gen.choose(0, config.groups - 1)
    } yield ChainIndex.unsafe(from, to)

  def chainIndexGenRelatedTo(broker: BrokerGroupInfo)(
      implicit config: GroupConfig): Gen[ChainIndex] =
    chainIndexGen.retryUntil(_.relateTo(broker))

  def chainIndexGenForBroker(broker: BrokerGroupInfo)(
      implicit config: GroupConfig): Gen[ChainIndex] =
    chainIndexGen.retryUntil(index => broker.contains(index.from))

  def chainIndexGenNotRelatedTo(broker: BrokerGroupInfo)(
      implicit config: GroupConfig): Gen[ChainIndex] =
    chainIndexGen.retryUntil(!_.relateTo(broker))

  def chainIndexFrom(groupIndex: GroupIndex)(implicit config: GroupConfig): Gen[ChainIndex] =
    Gen.choose(0, config.groups - 1).map(ChainIndex.unsafe(groupIndex.value, _))

  def keypairGen(groupIndex: GroupIndex)(
      implicit config: GroupConfig): Gen[(PrivateKey, PublicKey)] =
    Gen.const(()).map(_ => groupIndex.generateKey)

  def publicKeyGen(groupIndex: GroupIndex)(implicit config: GroupConfig): Gen[PublicKey] =
    keypairGen(groupIndex).map(_._2)

  def cliqueIdGen: Gen[CliqueId] =
    Gen.const(()).map(_ => CliqueId.generate)

  def groupNumPerBrokerGen(implicit config: GroupConfig): Gen[Int] =
    Gen.oneOf((1 to config.groups).filter(i => (config.groups % i) equals 0))

  def brokerInfoGen(implicit config: CliqueConfig): Gen[BrokerInfo] =
    for {
      cliqueId <- cliqueIdGen
      brokerId <- Gen.choose(0, config.brokerNum - 1)
      address  <- socketAddressGen
    } yield BrokerInfo.unsafe(cliqueId, brokerId, config.groupNumPerBroker, address)

  def cliqueInfoGen(implicit config: GroupConfig): Gen[CliqueInfo] =
    for {
      groupNumPerBroker <- groupNumPerBrokerGen
      peers             <- Gen.listOfN(config.groups / groupNumPerBroker, socketAddressGen)
      cid               <- cliqueIdGen
    } yield
      CliqueInfo.unsafe(cid,
                        AVector.from(peers.map(Option.apply)),
                        AVector.from(peers),
                        groupNumPerBroker)

  def interCliqueInfoGen(implicit config: GroupConfig): Gen[InterCliqueInfo] =
    for {
      groupNumPerBroker <- groupNumPerBrokerGen
      peers             <- Gen.listOfN(config.groups / groupNumPerBroker, socketAddressGen)
      cid               <- cliqueIdGen
    } yield InterCliqueInfo.unsafe(cid, AVector.from(peers), groupNumPerBroker)

  lazy val socketAddressGen: Gen[InetSocketAddress] =
    for {
      ip0  <- Gen.choose(0, 255)
      ip1  <- Gen.choose(0, 255)
      ip2  <- Gen.choose(0, 255)
      ip3  <- Gen.choose(0, 255)
      port <- portGen
    } yield new InetSocketAddress(s"$ip0.$ip1.$ip2.$ip3", port)

}

trait DefaultGenerators extends Generators {
  implicit def config: GroupConfig = new GroupConfig {
    override def groups: Int = 3
  }
}
