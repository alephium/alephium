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

package org.alephium.api

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.Configuration
import sttp.tapir.generic.auto._
import sttp.tapir.server._

import org.alephium.api.{TapirCodecs, TapirSchemasLike}
import org.alephium.api.model.ApiKey
import org.alephium.util.AVector

trait BaseEndpoint extends ErrorExamples with TapirCodecs with TapirSchemasLike with StrictLogging {
  import Endpoints._
  import ApiError._

  implicit val customConfiguration: Configuration =
    Configuration.default.withDiscriminator("type")

  def apiKeys: AVector[ApiKey]

  type BaseEndpointWithoutApi[I, O] =
    Endpoint[Unit, I, ApiError[_ <: StatusCode], O, Any]

  type BaseEndpoint[I, O] =
    PartialServerEndpoint[Option[ApiKey], Unit, I, ApiError[_ <: StatusCode], O, Any, Future]

  val baseEndpointWithoutApiKey: BaseEndpointWithoutApi[Unit, Unit] = endpoint
    .out(emptyOutput.description("Ok"))
    .errorOut(
      oneOf[ApiError[_ <: StatusCode]](
        error(BadRequest, { case BadRequest(_) => true }),
        error(InternalServerError, { case InternalServerError(_) => true }),
        error(NotFound, { case NotFound(_) => true }),
        error(ServiceUnavailable, { case ServiceUnavailable(_) => true }),
        error(Unauthorized, { case Unauthorized(_) => true }),
        error(GatewayTimeout, { case GatewayTimeout(_) => true })
      )
    )

  val baseEndpoint: BaseEndpoint[Unit, Unit] = baseEndpointWithoutApiKey
    .securityIn(auth.apiKey(header[Option[ApiKey]]("X-API-KEY")))
    .serverSecurityLogic { apiKeyToCheck =>
      Future.successful(BaseEndpoint.checkApiKey(apiKeys, apiKeyToCheck))
    }

  def serverLogic[I, O](endpoint: BaseEndpoint[I, O])(
      logic: I => Future[Either[ApiError[_ <: StatusCode], O]]
  ): ServerEndpoint[Any, Future] = {
    endpoint.serverLogic { _ =>
      { case input => logic(input) }
    }
  }
}

object BaseEndpoint {
  def checkApiKey(
      allApiKeys: AVector[ApiKey],
      apiKeyToCheck: Option[ApiKey]
  ): Either[ApiError[_ <: StatusCode], Unit] = {
    if (allApiKeys.isEmpty) {
      apiKeyToCheck match {
        case None =>
          Right(())
        case Some(_) =>
          Left(ApiError.Unauthorized("Api key not configured in server"))
      }
    } else {
      apiKeyToCheck match {
        case None =>
          Left(ApiError.Unauthorized("Missing api key"))
        case Some(toCheck) =>
          if (allApiKeys.map(_.value).contains(toCheck.value)) {
            Right(())
          } else {
            Left(ApiError.Unauthorized("Wrong api key"))
          }
      }
    }
  }
}
