package org.alephium.protocol.model

import org.alephium.protocol.config.CliqueConfig

class BrokerId private (val value: Int) extends AnyVal {
  def contains(index: GroupIndex)(implicit config: CliqueConfig): Boolean = {
    index.value >= (value * config.groupNumPerBroker) && index.value < (value + 1) * config.groupNumPerBroker
  }

  def containsRaw(index: Int)(implicit config: CliqueConfig): Boolean = {
    index >= (value * config.groupNumPerBroker) && index < (value + 1) * config.groupNumPerBroker
  }

  def intersect(cliqueInfo: CliqueInfo, brokerId: BrokerId)(
      implicit config: CliqueConfig): Boolean = {
    val groupFrom  = cliqueInfo.groupNumPerBroker * brokerId.value
    val groupUntil = groupFrom + cliqueInfo.groupNumPerBroker
    !(groupUntil < config.groupFrom || groupFrom > config.groupUntil)
  }

  override def toString: String = s"BrokerId($value)"
}

object BrokerId {
  def apply(value: Int)(implicit config: CliqueConfig): BrokerId = {
    assert(validate(value))
    new BrokerId(value)
  }

  def unsafe(value: Int): BrokerId = new BrokerId(value)

  def validate(brokerId: Int)(implicit config: CliqueConfig): Boolean =
    0 <= brokerId && brokerId < config.brokerNum
}
