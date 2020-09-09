package org.alephium.protocol.config

import org.alephium.protocol.model.NetworkType

trait ChainsConfig {
  def networkType: NetworkType
}
