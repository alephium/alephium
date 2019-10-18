package org.alephium.appserver

import java.net.InetAddress
import java.time.Duration

import com.typesafe.config.Config

case class RPCConfig(
    networkInterface: InetAddress,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration
)

object RPCConfig {
  def load(implicit config: Config): RPCConfig = {
    val rpc = config.getConfig("rpc")
    RPCConfig(InetAddress.getByName(rpc.getString("network.interface")),
              rpc.getDuration("blockflowFetch.maxAge"),
              rpc.getDuration("ask.timeout"))
  }
}
