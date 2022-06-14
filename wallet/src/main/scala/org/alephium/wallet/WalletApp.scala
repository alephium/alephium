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

package org.alephium.wallet

import java.nio.file.Paths

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future, Promise}

import com.typesafe.scalalogging.StrictLogging
import io.vertx.core.Vertx
import io.vertx.core.http.{HttpMethod, HttpServer}
import io.vertx.ext.web._
import io.vertx.ext.web.handler.CorsHandler
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{AVector, Service}
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.web._

class WalletApp(config: WalletConfig)(implicit
    val executionContext: ExecutionContext
) extends Service
    with StrictLogging {

  implicit private val groupConfig = new GroupConfig {
    override def groups: Int = config.blockflow.groups
  }

  val blockFlowClient: BlockFlowClient =
    BlockFlowClient.apply(
      config.blockflow.uri,
      config.blockflow.blockflowFetchMaxAge,
      config.blockflow.apiKey
    )

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private val secretDir = Paths.get(config.secretDir.toString)
  val walletService: WalletService =
    WalletService.apply(blockFlowClient, secretDir, config.lockingTimeout)

  val walletServer: WalletServer =
    new WalletServer(
      walletService,
      config.blockflow.blockflowFetchMaxAge,
      config.apiKey
    )

  val routes: AVector[Router => Route] = walletServer.routes :+ walletServer.docsRoute

  private val bindingPromise: Promise[HttpServer] = Promise()

  override val subServices: ArraySeq[Service] = ArraySeq(walletService)

  protected def startSelfOnce(): Future[Unit] = {
    config.port match {
      case None => Future.successful(())
      case Some(port) =>
        val vertx  = Vertx.vertx()
        val router = Router.router(vertx)
        vertx
          .fileSystem()
          .existsBlocking(
            "META-INF/resources/webjars/swagger-ui/"
          ) // Fix swagger ui being not found on the first call
        val server: HttpServer = vertx.createHttpServer().requestHandler(router)

        // scalastyle:off magic.number
        router
          .route()
          .handler(
            CorsHandler
              .create(".*.")
              .allowedMethod(HttpMethod.GET)
              .allowedMethod(HttpMethod.POST)
              .allowedMethod(HttpMethod.PUT)
              .allowedMethod(HttpMethod.HEAD)
              .allowedMethod(HttpMethod.OPTIONS)
              .allowedHeader("*")
              .allowCredentials(true)
              .maxAgeSeconds(1800)
          )
        // scalastyle:on magic.number

        routes.foreach(route => route(router))
        for {
          binding <- server.listen(port, "127.0.0.1").asScala
        } yield {
          bindingPromise.success(binding)
          logger.info(s"Listening wallet http request on $binding")
        }
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    config.port match {
      case None => Future.successful(())
      case Some(_) =>
        for {
          binding <- bindingPromise.future
          _       <- binding.close().asScala
        } yield {
          logger.info("Wallet stopped")
        }
    }
}
