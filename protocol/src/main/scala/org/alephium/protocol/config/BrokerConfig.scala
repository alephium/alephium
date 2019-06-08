package org.alephium.protocol.config

import org.alephium.protocol.model.BrokerId

trait BrokerConfig extends CliqueConfig {
  def brokerId: BrokerId

  def isMaster: Boolean

  lazy val groupFrom: Int = brokerId.value * groupNumPerBroker

  lazy val groupUntil: Int = (brokerId.value + 1) * groupNumPerBroker
}
