package org.alephium.protocol.model

import java.net.InetSocketAddress

import org.alephium.protocol.SafeSerdeImpl
import org.alephium.protocol.config.{CliqueConfig, GroupConfig}
import org.alephium.serde._
import org.alephium.util.AVector

// All the groups [0, ..., G-1] are divided into G/gFactor continuous groups
// Assume the peers are ordered according to the groups they correspond to
final case class CliqueInfo private (
    id: CliqueId,
    externalAddresses: AVector[Option[InetSocketAddress]],
    internalAddresses: AVector[InetSocketAddress],
    groupNumPerBroker: Int
) { self =>
  def brokerNum: Int = internalAddresses.length

  def cliqueConfig: CliqueConfig = new CliqueConfig {
    val brokerNum: Int = self.brokerNum
    val groups: Int    = self.brokerNum * self.groupNumPerBroker
  }

  def brokers: AVector[BrokerInfo] = {
    internalAddresses.mapWithIndex { (internalAddress, index) =>
      val externalAddressesOpt = externalAddresses(index)
      val brokerAddress        = externalAddressesOpt.fold(internalAddress)(identity)
      BrokerInfo.unsafe(id, index, groupNumPerBroker, brokerAddress)
    }
  }

  def masterAddress: InetSocketAddress = internalAddresses.head

  def selfBrokerInfo(implicit brokerConfig: BrokerGroupInfo): BrokerInfo =
    brokers(brokerConfig.brokerId)

  def brokerInfoUnsafe(brokerId: Int): BrokerInfo = brokers(brokerId)

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def interCliqueInfo: Option[InterCliqueInfo] =
    Option.when(externalAddresses.forall(_.nonEmpty))(
      InterCliqueInfo.unsafe(id, externalAddresses.map(_.get), groupNumPerBroker)
    )
}

object CliqueInfo extends SafeSerdeImpl[CliqueInfo, GroupConfig] {
  val _serde: Serde[CliqueInfo] =
    Serde.forProduct4(unsafe,
                      t => (t.id, t.externalAddresses, t.internalAddresses, t.groupNumPerBroker))

  override def validate(info: CliqueInfo)(implicit config: GroupConfig): Either[String, Unit] = {
    val cliqueGroups = info.brokerNum * info.groupNumPerBroker
    if (cliqueGroups != config.groups)
      Left(s"Number of groups: got: $cliqueGroups expect: ${config.groups}")
    else Right(())
  }

  def unsafe(id: CliqueId,
             externalAddresses: AVector[Option[InetSocketAddress]],
             internalAddresses: AVector[InetSocketAddress],
             groupNumPerBroker: Int): CliqueInfo = {
    new CliqueInfo(id, externalAddresses, internalAddresses, groupNumPerBroker)
  }
}

final case class InterCliqueInfo(
    id: CliqueId,
    externalAddresses: AVector[InetSocketAddress],
    groupNumPerBroker: Int
) {
  def brokerNum: Int = externalAddresses.length

  def brokers: AVector[BrokerInfo] = {
    externalAddresses.mapWithIndex { (externalAddress, index) =>
      BrokerInfo.unsafe(id, index, groupNumPerBroker, externalAddress)
    }
  }
}

object InterCliqueInfo extends SafeSerdeImpl[InterCliqueInfo, GroupConfig] {
  val _serde: Serde[InterCliqueInfo] =
    Serde.forProduct3(unsafe, t => (t.id, t.externalAddresses, t.groupNumPerBroker))

  override def validate(info: InterCliqueInfo)(
      implicit config: GroupConfig): Either[String, Unit] = {
    val cliqueGroup = info.brokerNum * info.groupNumPerBroker
    if (cliqueGroup != config.groups)
      Left(s"Number of groups: got: $cliqueGroup expect: ${config.groups}")
    else Right(())
  }

  def unsafe(id: CliqueId,
             externalAddresses: AVector[InetSocketAddress],
             groupNumPerBroker: Int): InterCliqueInfo = {
    new InterCliqueInfo(id, externalAddresses, groupNumPerBroker)
  }
}
