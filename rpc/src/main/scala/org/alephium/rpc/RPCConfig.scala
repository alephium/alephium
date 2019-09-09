package org.alephium.rpc

import com.typesafe.config.Config
import java.time.Duration

case class RPCConfig(
    networkInterface: String,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration
)

object RPCConfig {
  def load(implicit config: Config): RPCConfig = {
    val rpc = config.getConfig("rpc")
    RPCConfig(rpc.getString("network.interface"),
              rpc.getDuration("blockflowFetch.maxAge"),
              rpc.getDuration("ask.timeout"))
  }
}
