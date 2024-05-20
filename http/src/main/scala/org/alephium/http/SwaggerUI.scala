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

import scala.concurrent.Future

import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir._
import sttp.tapir.files.staticResourcesGetServerEndpoint
import sttp.tapir.server.ServerEndpoint

import org.alephium.util.AVector

/*
 * Extracted, with some modifications, from sttp.tapir.swagger.SwaggerUI
 */
object SwaggerUI {
  private val swaggerVersion = {
    val p = new Properties()
    val pomProperties =
      getClass.getResourceAsStream("/META-INF/maven/org.webjars/swagger-ui/pom.properties")
    try p.load(pomProperties)
    finally pomProperties.close()
    p.getProperty("version")
  }

  // scalastyle:off method.length
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def apply(
      openapiContent: String,
      contextPath: String = "docs",
      openapiFileName: String = "openapi.json"
  ): AVector[ServerEndpoint[Any, Future]] = {
    val baseEndpoint = infallibleEndpoint.get.in(contextPath)
    val redirectOutput =
      statusCode(StatusCode.PermanentRedirect).and(header[String](HeaderNames.Location))

    val openapiEndpoint = baseEndpoint
      .in(openapiFileName)
      .out(stringBody)
      .serverLogicPure[Future](_ => Right(openapiContent))

    val swaggerInitializerJsWithExtended =
      s"""|window.onload = function() {
          |  window.ui = SwaggerUIBundle({
          |    url: "/docs/$openapiFileName",
          |    tryItOutEnabled: true,
          |    dom_id: '#swagger-ui',
          |    deepLinking: true,
          |    presets: [
          |      SwaggerUIBundle.presets.apis,
          |      SwaggerUIStandalonePreset
          |    ],
          |    plugins: [
          |      SwaggerUIBundle.plugins.DownloadUrl
          |    ],
          |    layout: "StandaloneLayout"
          |  });
          |};
          |""".stripMargin

    val textJavascriptUtf8: EndpointIO.Body[String, String] = stringBodyUtf8AnyFormat(
      Codec.string.format(CodecFormat.TextJavascript())
    )
    val swaggerInitializerJsEndpoint =
      baseEndpoint
        .in("swagger-initializer.js")
        .out(textJavascriptUtf8)
        .serverLogicPure[Future](_ => Right(swaggerInitializerJsWithExtended))

    val resourcesEndpoint = staticResourcesGetServerEndpoint[Future](contextPath)(
      SwaggerUI.getClass.getClassLoader,
      s"META-INF/resources/webjars/swagger-ui/$swaggerVersion/"
    )

    val redirectToSlashEndpoint = baseEndpoint
      .in(noTrailingSlash)
      .in(queryParams)
      .out(redirectOutput)
      .serverLogicPure[Future] { params =>
        val queryString = if (params.toSeq.nonEmpty) s"?${params.toString}" else ""
        Right(s"/docs/$queryString")
      }

    AVector(
      openapiEndpoint,
      redirectToSlashEndpoint,
      swaggerInitializerJsEndpoint,
      resourcesEndpoint
    )
  }
}
