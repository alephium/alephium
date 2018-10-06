package org.alephium.flow.constant
import com.typesafe.config.ConfigFactory

trait WithConfig {

  val config = ConfigFactory.load().getConfig("alephium")
}
