package org.alephium

import java.time.Duration

import com.typesafe.config.Config

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
