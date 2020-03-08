package org.alephium.appserver

import java.net.InetAddress

import com.typesafe.config.Config

import org.alephium.util.Duration

final case class RPCConfig(
    networkInterface: InetAddress,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration
)

object RPCConfig {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def load(implicit config: Config): RPCConfig = {
    val rpc = config.getConfig("rpc")
    RPCConfig(
      InetAddress.getByName(rpc.getString("network.interface")),
      Duration.from(rpc.getDuration("blockflowFetch.maxAge")).get,
      Duration.from(rpc.getDuration("ask.timeout")).get
    )
  }
}
