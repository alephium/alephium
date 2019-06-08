package org.alephium.protocol.model

import org.alephium.protocol.config.{BrokerConfig, CliqueConfig}
import org.alephium.serde.{Serde, Serializer}

class BrokerId private (val value: Int) extends AnyVal {
  def contains(index: GroupIndex)(implicit config: CliqueConfig): Boolean = {
    index.value >= (value * config.groupNumPerBroker) && index.value < (value + 1) * config.groupNumPerBroker
  }

  def containsRaw(index: Int)(implicit config: CliqueConfig): Boolean = {
    index >= (value * config.groupNumPerBroker) && index < (value + 1) * config.groupNumPerBroker
  }

  def intersect(cliqueInfo: CliqueInfo, brokerId: BrokerId)(
      implicit config: BrokerConfig): Boolean = {
    val groupFrom  = cliqueInfo.groupNumPerBroker * brokerId.value
    val groupUntil = groupFrom + cliqueInfo.groupNumPerBroker
    !(groupUntil < config.groupFrom || groupFrom > config.groupUntil)
  }

  override def toString: String = s"BrokerId($value)"
}

object BrokerId {
  implicit val serializer: Serializer[BrokerId] = Serializer.forProduct1(_.value)

  class Unsafe(val value: Int) extends AnyVal {
    def validate(implicit config: CliqueConfig): Either[String, BrokerId] = {
      if (0 <= value && value < config.brokerNum) Right(BrokerId.unsafe(value))
      else Left("Invalid value for broker id")
    }
  }
  object Unsafe {
    val serde: Serde[Unsafe] = Serde.forProduct1(new Unsafe(_), _.value)
  }

  def apply(value: Int)(implicit config: CliqueConfig): BrokerId = {
    assert(validate(value))
    new BrokerId(value)
  }

  def unsafe(value: Int): BrokerId = new BrokerId(value)

  def validate(brokerId: Int)(implicit config: CliqueConfig): Boolean =
    0 <= brokerId && brokerId < config.brokerNum
}
