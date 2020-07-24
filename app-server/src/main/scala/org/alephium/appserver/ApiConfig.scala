package org.alephium.appserver

import java.net.InetAddress

import com.typesafe.config.Config

import org.alephium.protocol.ALF.Hash
import org.alephium.util.{Duration, Hex}

final case class ApiConfig(
    networkInterface: InetAddress,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration,
    apiKeyHash: Hash
)

object ApiConfig {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def load(implicit config: Config): ApiConfig = {
    val api = config.getConfig("api")
    ApiConfig(
      InetAddress.getByName(api.getString("network.interface")),
      Duration.from(api.getDuration("blockflowFetch.maxAge")).get,
      Duration.from(api.getDuration("ask.timeout")).get,
      Hash.from(Hex.from(api.getString("apiKeyHash")).get).get
    )
  }
}
