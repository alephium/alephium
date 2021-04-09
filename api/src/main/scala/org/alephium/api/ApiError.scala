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

import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import sttp.model.StatusCode
import sttp.tapir.{FieldName, Schema}
import sttp.tapir.SchemaType.{SObjectInfo, SProduct}

sealed trait ApiError {
  def status: StatusCode
  def detail: String
}

object ApiError {

  private def encodeApiError[A <: ApiError]: Encoder[A] =
    new Encoder[A] {
      final def apply(apiError: A): Json =
        Json.obj(
          ("status", Json.fromInt(apiError.status.code)),
          ("detail", Json.fromString(apiError.detail))
        )
    }

  final case class Unauthorized(val detail: String) extends ApiError {
    final val status: StatusCode = StatusCode.Unauthorized
  }

  object Unauthorized {
    implicit val encoder: Encoder[Unauthorized] = new Encoder[Unauthorized] {
      val baseEncoder = deriveEncoder[Unauthorized]
      final def apply(unauthorized: Unauthorized): Json =
        encodeApiError[Unauthorized](unauthorized).deepMerge(baseEncoder(unauthorized))
    }

    implicit val decoder: Decoder[Unauthorized] = deriveDecoder
    implicit val schema: Schema[Unauthorized] =
      Schema(
        SProduct(
          SObjectInfo("Unauthorized"),
          List(
            FieldName("status") -> Schema.schemaForInt,
            FieldName("detail") -> Schema.schemaForString
          )
        )
      )
  }

  final case class BadRequest(val detail: String) extends ApiError {
    final val status: StatusCode = StatusCode.BadRequest
  }
  object BadRequest {
    implicit val encoder: Encoder[BadRequest] = new Encoder[BadRequest] {
      val baseEncoder = deriveEncoder[BadRequest]
      final def apply(badRequest: BadRequest): Json =
        encodeApiError[BadRequest](badRequest).deepMerge(baseEncoder(badRequest))
    }
    implicit val decoder: Decoder[BadRequest] = deriveDecoder
    implicit val schema: Schema[BadRequest] =
      Schema(
        SProduct(
          SObjectInfo("BadRequest"),
          List(
            FieldName("status") -> Schema.schemaForInt,
            FieldName("detail") -> Schema.schemaForString
          )
        )
      )
  }

  final case class NotFound(resource: String) extends ApiError {
    final val status: StatusCode = StatusCode.NotFound
    final val detail: String     = s"$resource not found"
  }

  object NotFound {
    implicit val encoder: Encoder[NotFound] = new Encoder[NotFound] {
      val baseEncoder = deriveEncoder[NotFound]
      final def apply(notFound: NotFound): Json =
        encodeApiError[NotFound](notFound).deepMerge(baseEncoder(notFound))
    }

    implicit val decoder: Decoder[NotFound] = deriveDecoder
    implicit val schema: Schema[NotFound] =
      Schema(
        SProduct(
          SObjectInfo("NotFound"),
          List(
            FieldName("status") -> Schema.schemaForInt,
            FieldName("detail") -> Schema.schemaForString
          )
        )
      )
  }

  final case class ServiceUnavailable(val detail: String) extends ApiError {
    final val status: StatusCode = StatusCode.ServiceUnavailable
  }

  object ServiceUnavailable {
    implicit val encoder: Encoder[ServiceUnavailable] = new Encoder[ServiceUnavailable] {
      val baseEncoder = deriveEncoder[ServiceUnavailable]
      final def apply(serviceUnavailable: ServiceUnavailable): Json =
        encodeApiError[ServiceUnavailable](serviceUnavailable).deepMerge(
          baseEncoder(serviceUnavailable)
        )
    }

    implicit val decoder: Decoder[ServiceUnavailable] = deriveDecoder
    implicit val schema: Schema[ServiceUnavailable] =
      Schema(
        SProduct(
          SObjectInfo("ServiceUnavailable"),
          List(
            FieldName("status") -> Schema.schemaForInt,
            FieldName("detail") -> Schema.schemaForString
          )
        )
      )
  }

  final case class InternalServerError(val detail: String) extends ApiError {
    final val status: StatusCode = StatusCode.InternalServerError
  }

  object InternalServerError {
    implicit val encoder: Encoder[InternalServerError] = new Encoder[InternalServerError] {
      val baseEncoder = deriveEncoder[InternalServerError]
      final def apply(internalServerError: InternalServerError): Json =
        encodeApiError[InternalServerError](internalServerError).deepMerge(
          baseEncoder(internalServerError)
        )
    }

    implicit val decoder: Decoder[InternalServerError] = deriveDecoder
    implicit val schema: Schema[InternalServerError] =
      Schema(
        SProduct(
          SObjectInfo("InternalServerError"),
          List(
            FieldName("status") -> Schema.schemaForInt,
            FieldName("detail") -> Schema.schemaForString
          )
        )
      )
  }

  implicit val decoder: Decoder[ApiError] = new Decoder[ApiError] {
    def dec(c: HCursor, status: StatusCode): Decoder.Result[ApiError] =
      status match {
        case StatusCode.BadRequest          => BadRequest.decoder(c)
        case StatusCode.InternalServerError => InternalServerError.decoder(c)
        case StatusCode.NotFound            => NotFound.decoder(c)
        case StatusCode.ServiceUnavailable  => ServiceUnavailable.decoder(c)
        case StatusCode.Unauthorized        => Unauthorized.decoder(c)
        case _                              => Left(DecodingFailure(s"$status not supported", c.history))
      }
    final def apply(c: HCursor): Decoder.Result[ApiError] =
      for {
        statusAsInt <- c.downField("status").as[Int]
        apiError    <- dec(c, StatusCode(statusAsInt))
      } yield apiError
  }

  implicit val encoder: Encoder[ApiError] = new Encoder[ApiError] {
    final def apply(apiError: ApiError): Json =
      apiError match {
        case badRequest: BadRequest => BadRequest.encoder(badRequest)
        case internalServerError: InternalServerError =>
          InternalServerError.encoder(internalServerError)
        case notFound: NotFound => NotFound.encoder(notFound)
        case serviceUnavailable: ServiceUnavailable =>
          ServiceUnavailable.encoder(serviceUnavailable)
        case unauthorized: Unauthorized => Unauthorized.encoder(unauthorized)
        case _                          => encodeApiError[ApiError](apiError)
      }
  }

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  implicit val schema: Schema[ApiError] =
    Schema.oneOf[ApiError, StatusCode](_.status, _.toString)(
      StatusCode.BadRequest         -> BadRequest.schema,
      StatusCode.NotFound           -> NotFound.schema,
      StatusCode.ServiceUnavailable -> ServiceUnavailable.schema,
      StatusCode.Unauthorized       -> Unauthorized.schema
    )
}
