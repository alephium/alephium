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

import scala.collection.immutable.ArraySeq
import scala.concurrent._

import com.typesafe.scalalogging.StrictLogging
import sttp.client3.HttpClientFutureBackend
import sttp.client3.Request
import sttp.model.{StatusCode, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter

import org.alephium.api.{ApiError, BaseEndpoint}
import org.alephium.api.model.ApiKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.util.{AVector, Service}
import org.alephium.util.Utils.getStackTrace

// scalastyle:off method.length
class EndpointSender(val maybeApiKey: Option[ApiKey])(implicit
    val groupConfig: GroupConfig,
    val executionContext: ExecutionContext
) extends BaseEndpoint
    with SttpClientInterpreter
    with Service
    with StrictLogging {

  private val backend = HttpClientFutureBackend()

  protected def startSelfOnce(): Future[Unit] = Future.unit

  protected def stopSelfOnce(): Future[Unit] = {
    backend.close()
  }

  override def subServices: ArraySeq[Service] = ArraySeq.empty

  override val apiKeys: AVector[ApiKey] = AVector.empty

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
