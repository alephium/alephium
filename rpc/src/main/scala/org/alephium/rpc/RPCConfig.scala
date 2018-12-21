package org.alephium.rpc

import com.typesafe.config.Config
import java.time.Duration

case class RPCConfig(viewerBlockAgeLimit: Duration)

object RPCConfig {
  def load(implicit config: Config): RPCConfig = {
    val rpc = config.getConfig("rpc")
    RPCConfig(rpc.getDuration("viewerBlockAgeLimit"))
  }
}
