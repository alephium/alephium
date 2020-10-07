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

package org.alephium.wallet.web

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{OverflowStrategy, QueueOfferResult, StreamTcpException}
import akka.stream.scaladsl.{Sink, Source}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Decoder, Json}

trait HttpClient {
  def request[A: Decoder: ClassTag](httpRequest: HttpRequest): Future[Either[String, A]]
}

object HttpClient {
  def apply(bufferSize: Int, overflowStrategy: OverflowStrategy)(
      implicit system: ActorSystem,
      executionContext: ExecutionContext
  ): HttpClient = new Impl(bufferSize, overflowStrategy)

  private class Impl(bufferSize: Int, overflowStrategy: OverflowStrategy)(
      implicit system: ActorSystem,
      executionContext: ExecutionContext
  ) extends HttpClient
      with FailFastCirceSupport {

    def request[A: Decoder: ClassTag](httpRequest: HttpRequest): Future[Either[String, A]] =
      requestResponse(httpRequest)
        .flatMap {
          case HttpResponse(code, _, entity, _) if code.isSuccess =>
            entity.getContentType match {
              case ContentTypes.`application/json` =>
                Unmarshal(entity).to[Either[io.circe.Error, Json]].map {
                  case Left(error) => Left(s"Cannot decode entity as json: ${error.getMessage}")
                  case Right(json) =>
                    json.as[A].left.map { error =>
                      s"Cannot decode json: ${json} as a ${classTag[A]}. Error: $error"
                    }
                }
              case other =>
                Future.successful(Left(s"$other content type not supported"))
            }
          case HttpResponse(code, _, _, _) =>
            Future.successful(Left(s"$httpRequest failed with code $code"))
        }
        .recover {
          case streamException: StreamTcpException =>
            streamException.getCause match {
              case connectException: java.net.ConnectException =>
                Left(s"${connectException.getMessage} (${httpRequest.uri})")
              case _ => Left(s"Unexpected error: $streamException")
            }
          case error =>
            Left(s"Unexpected error: $error")
        }

    // Inspired from: https://doc.akka.io/docs/akka-http/current/client-side/host-level.html#using-a-host-connection-pool
    private val superPoolFlow = Http().superPool[Promise[HttpResponse]]()
    private val queue =
      Source
        .queue[(HttpRequest, Promise[HttpResponse])](bufferSize, overflowStrategy)
        .via(superPoolFlow)
        .to(Sink.foreach({
          case (Success(response), promise)  => promise.success(response)
          case (Failure(exception), promise) => promise.failure(exception)
        }))
        .run()

    private def requestResponse(request: HttpRequest): Future[HttpResponse] = {
      val responsePromise = Promise[HttpResponse]()
      queue.offer(request -> responsePromise).flatMap {
        case QueueOfferResult.Enqueued => responsePromise.future
        case QueueOfferResult.Dropped =>
          Future.failed(new RuntimeException("Queue overflowed. Try again later."))
        case QueueOfferResult.Failure(ex) => Future.failed(ex)
        case QueueOfferResult.QueueClosed =>
          Future
            .failed(
              new RuntimeException(
                "Queue was closed (pool shut down) while running the request. Try again later."))
      }
    }
  }
}
