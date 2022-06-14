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

import scala.concurrent._

import sttp.client3.Request
import sttp.client3.asynchttpclient.future.AsyncHttpClientFutureBackend
import sttp.model.{StatusCode, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter

import org.alephium.api.{ApiError, BaseEndpoint}
import org.alephium.api.model.ApiKey
import org.alephium.util.Utils.getStackTrace

// scalastyle:off method.length
trait EndpointSender extends BaseEndpoint with SttpClientInterpreter {

  private val backend = AsyncHttpClientFutureBackend()

  val maybeApiKey: Option[ApiKey]

  def createRequest[I, O](
      endpoint: BaseEndpoint[I, O],
      params: I,
      uri: Uri
  ): Request[Either[ApiError[_ <: StatusCode], O], Any] = {
    toSecureRequest(endpoint.endpoint, Some(uri))
      .apply(maybeApiKey)
      .apply(params)
      .mapResponse(handleDecodeFailures)
  }

  private def handleDecodeFailures[O](
      dr: DecodeResult[Either[ApiError[_ <: StatusCode], O]]
  ): Either[ApiError[_ <: StatusCode], O] =
    dr match {
      case DecodeResult.Value(v) => v
      case DecodeResult.Error(_, e) =>
        logger.error(getStackTrace(e))
        Left(ApiError.InternalServerError(e.getMessage))
      case f => Left(ApiError.InternalServerError(s"Cannot decode: $f"))
    }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def send[A, B](endpoint: BaseEndpoint[A, B], params: A, uri: Uri)(implicit
      executionContext: ExecutionContext
  ): Future[Either[ApiError[_ <: StatusCode], B]] = {
    backend
      .send(createRequest(endpoint, params, uri))
      .map(_.body)
  }
}
