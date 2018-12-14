package org.alephium.flow.constant
import com.typesafe.config.ConfigFactory

trait DefaultConfig {

  val config = ConfigFactory.load().getConfig("alephium")
}
