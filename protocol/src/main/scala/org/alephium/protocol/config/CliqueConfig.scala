package org.alephium.protocol.config

trait CliqueConfig extends GroupConfig {
  def brokerNum: Int

  lazy val groupNumPerBroker: Int = groups / brokerNum

  def validate(brokerId: Int): Boolean = brokerId >= 0 && brokerId < brokerNum
}
