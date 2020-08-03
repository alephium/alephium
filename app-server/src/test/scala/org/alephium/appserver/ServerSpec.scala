package org.alephium.appserver

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}

import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.util.AlephiumSpec

class ServerSpec extends AlephiumSpec with ScalaFutures {
  override implicit val patienceConfig = PatienceConfig(timeout = Span(1, Minutes))

  it should "start and stop correctly" in {
    val rootPath                                    = Platform.getRootPath()
    val rawConfig                                   = Configs.parseConfig(rootPath)
    implicit val config: AlephiumConfig             = AlephiumConfig.load(rawConfig).toOption.get
    implicit val apiConfig: ApiConfig               = ApiConfig.load(rawConfig).toOption.get
    implicit val system: ActorSystem                = ActorSystem("Root", rawConfig)
    implicit val executionContext: ExecutionContext = system.dispatcher

    val server = new ServerImpl(rootPath)

    server.start().futureValue is ()
    server.stop().futureValue is ()
  }
}
