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

package org.alephium.http

import java.util.Properties

import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.{Route, Router}
import io.vertx.ext.web.handler.StaticHandler

/*
 * Extracted, with some modifications, from sttp.tapir.swagger.vertx.SwaggerVertx
 */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
class SwaggerVertx(
    openapiContent: String,
    contextPath: String = "docs",
    openapiFileName: String = "openapi.json",
    parameters: Option[String] = Some("tryItOutEnabled=true")
) {

  private val staticHandler = {
    val p = new Properties()
    val pomProperties =
      getClass.getResourceAsStream("/META-INF/maven/org.webjars/swagger-ui/pom.properties")
    try p.load(pomProperties)
    finally pomProperties.close()
    val swaggerVersion = p.getProperty("version")
    StaticHandler
      .create(s"META-INF/resources/webjars/swagger-ui/$swaggerVersion/")
      .setCachingEnabled(false)
      .setDefaultContentEncoding("UTF-8")
  }

  private val redirectUrl = {
    val postFix = parameters match {
      case Some(params) => "&" + params
      case None         => ""
    }
    s"/$contextPath/index.html?url=/$contextPath/$openapiFileName" + postFix
  }
  def route(router: Router): Route = {
    router
      .route(HttpMethod.GET, s"/$contextPath")
      .handler { ctx =>
        ctx.redirect(redirectUrl)
        ()
      }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    val contentType        = s"application/${openapiFileName.split("\\.").last}"
    val contentDisposition = s"""inline; filename="$openapiFileName""""
    router
      .route(HttpMethod.GET, s"/$contextPath/$openapiFileName")
      .handler { ctx =>
        ctx
          .response()
          .putHeader("Content-Type", contentType)
          .putHeader("Content-Disposition", contentDisposition)
          .end(openapiContent)
        ()
      }

    router.route(s"/$contextPath/*").handler(staticHandler)
  }
}
