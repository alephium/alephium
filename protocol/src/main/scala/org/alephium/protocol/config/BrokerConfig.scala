package org.alephium.protocol.config

import org.alephium.protocol.model.BrokerGroupInfo

trait BrokerConfig extends GroupConfig with CliqueConfig with BrokerGroupInfo {
  def brokerId: Int
}
