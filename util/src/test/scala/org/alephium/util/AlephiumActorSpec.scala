package org.alephium.util

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestKitBase}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll

class AlephiumActorSpec(val name: String) extends AlephiumActorSpecLike

trait AlephiumActorSpecLike
    extends TestKitBase
    with ImplicitSender
    with AlephiumSpec
    with BeforeAndAfterAll {

  def name: String

  implicit lazy val system: ActorSystem =
    ActorSystem(name, ConfigFactory.parseString(AlephiumActorSpec.config))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}

object AlephiumActorSpec {
  val config: String =
    """
      |akka {
      |  loglevel = "DEBUG"
      |  loggers = ["akka.event.slf4j.Slf4jLogger"]
      |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
      |  
      |  // io.tcp.trace-logging = on
      |
      |  actor {
      |    debug {
      |      unhandled = on
      |    }
      |
      |    guardian-supervisor-strategy = "org.alephium.util.DefaultStrategy"
      |  }
      |}
    """.stripMargin
}
