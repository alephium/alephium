package org.alephium.flow

trait WithConfig {
  implicit def config: PlatformConfig = WithConfig.defaultConfig
}

object WithConfig {
  val defaultConfig: PlatformConfig = PlatformConfig.load()
}
