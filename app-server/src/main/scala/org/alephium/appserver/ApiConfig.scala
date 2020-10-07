package org.alephium.appserver

import java.net.InetAddress

import com.typesafe.config.Config
import pureconfig.ConfigReader.Result
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import org.alephium.crypto.Sha256
import org.alephium.flow.setting.PureConfigUtils._
import org.alephium.util.Duration

final case class ApiConfig(
    networkInterface: InetAddress,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration,
    apiKeyHash: Option[Sha256]
)

object ApiConfig {
  def source(config: Config): ConfigSource = {
    val path          = "alephium.api"
    val configLocated = if (config.hasPath(path)) config.getConfig(path) else config
    ConfigSource.fromConfig(configLocated)
  }

  def load(config: Config): Result[ApiConfig] = source(config).load[ApiConfig]
  def loadOrThrow(config: Config): ApiConfig  = source(config).loadOrThrow[ApiConfig]
}
