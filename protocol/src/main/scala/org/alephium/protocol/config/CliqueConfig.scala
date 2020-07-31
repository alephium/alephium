package org.alephium.protocol.config

trait CliqueConfig extends GroupConfig {
  def brokerNum: Int

  def groupNumPerBroker: Int = groups / brokerNum
}
