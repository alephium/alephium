package org.alephium.protocol.config

trait CliqueConfig extends GroupConfig {
  def brokerNum: Int

  lazy val groupNumPerBroker: Int = groups / brokerNum
}
