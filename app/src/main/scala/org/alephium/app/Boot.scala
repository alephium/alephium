// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import java.nio.file.{Files, Path, Paths}

import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import com.typesafe.config.{Config, ConfigRenderOptions}
import com.typesafe.scalalogging.StrictLogging

import io.prometheus.client.hotspot.DefaultExports

import org.alephium.flow.setting.{AlephiumConfig, Configs, Platform}
import org.alephium.protocol.model.Block
import org.alephium.util.{AVector, Duration, Files => AFiles}

object Boot extends App with StrictLogging {
  try {
    val rootPath = sys.env.get("ALEPHIUM_HOME") match {
      case Some(rawPath) => Paths.get(rawPath)
      case None          => AFiles.homeDir.resolve(".alephium")
    }
    if (!Files.exists(rootPath)) rootPath.toFile.mkdir()

    (new BootUp).init()
  } catch {
    case error: Throwable =>
      logger.error(s"Cannot initialize system: $error")
      sys.exit(1)
  }
}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
class BootUp extends StrictLogging {
  val rootPath: Path                  = Platform.getRootPath()
  val typesafeConfig: Config          = Configs.parseConfigAndValidate(rootPath)
  implicit val config: AlephiumConfig = AlephiumConfig.load(typesafeConfig, "alephium")
  implicit val apiConfig: ApiConfig   = ApiConfig.load(typesafeConfig, "alephium.api")
  val flowSystem: ActorSystem         = ActorSystem("flow", typesafeConfig)

  @SuppressWarnings(Array("org.wartremover.warts.GlobalExecutionContext"))
  implicit val executionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  // Register the default Hotspot (JVM) collectors for Prometheus
  DefaultExports.initialize()

  val server: Server = Server(rootPath, flowSystem)

  def init(): Unit = {
    logConfig()

    server
      .start()
      .onComplete {
        case Success(_) => ()
        case Failure(e) =>
          logger.error("Fatal error during initialization.", e)
          stop()
      }

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      stop()
    }))
  }

  CoordinatedShutdown(flowSystem).addTask(
    CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
    "Shutdown services"
  ) { () =>
    for {
      _ <- server.stop()
    } yield Done
  }

  val shutdownTimeout: Duration = Duration.ofSecondsUnsafe(10)
  def stop(): Unit = {
    Await.result(flowSystem.terminate(), shutdownTimeout.asScala)
    ()
  }

  def logConfig(): Unit = {
    val renderOptions =
      ConfigRenderOptions.defaults().setOriginComments(false).setComments(true).setJson(false)
    logger.debug(typesafeConfig.root().render(renderOptions))

    val digests = config.genesisBlocks.map(showBlocks).mkString("-")
    logger.info(s"Genesis digests: $digests")
  }

  def showBlocks(blocks: AVector[Block]): String = blocks.map(_.shortHex).mkString("-")
}
