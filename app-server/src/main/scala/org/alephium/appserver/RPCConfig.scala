package org.alephium.appserver

import java.net.InetAddress

import com.typesafe.config.Config

import org.alephium.protocol.ALF.Hash
import org.alephium.util.{Duration, Hex}

final case class RPCConfig(
    networkInterface: InetAddress,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration,
    apiKeyHash: Hash
)

object RPCConfig {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def load(implicit config: Config): RPCConfig = {
    val rpc = config.getConfig("rpc")
    RPCConfig(
      InetAddress.getByName(rpc.getString("network.interface")),
      Duration.from(rpc.getDuration("blockflowFetch.maxAge")).get,
      Duration.from(rpc.getDuration("ask.timeout")).get,
      Hash.from(Hex.from(rpc.getString("apiKeyHash")).get).get
    )
  }
}
