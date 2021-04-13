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

import io.circe.{Codec, Decoder, Encoder, Json}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import sttp.model.StatusCode
import sttp.tapir.{FieldName, Schema}
import sttp.tapir.SchemaType.{SObjectInfo, SProduct}

sealed trait ApiError[S <: StatusCode] {
  def detail: String
}

object ApiError {

  sealed abstract class Companion[S <: StatusCode, E <: ApiError[S]](val statusCode: S) {
    def description: String = this.getClass.getSimpleName.dropRight(1)

    def specificEncoder: Option[Encoder[E]] = None

    lazy val encoder: Encoder[E] = new Encoder[E] {
      val baseEncoder: Encoder[E] =
        new Encoder[E] {
          final def apply(apiError: E): Json =
            Json.obj(
              ("detail", Json.fromString(apiError.detail))
            )
        }

      final def apply(e: E): Json =
        specificEncoder match {
          case None =>
            baseEncoder(e)
          case Some(specific) =>
            baseEncoder(e).deepMerge(specific(e))
        }
    }

    def decoder: Decoder[E]

    implicit lazy val codec: Codec[E] = Codec.from(decoder, encoder)

    def apiErrorSchema(
        fields: List[(FieldName, Schema[_])]
    ): Schema[E] = {
      Schema(
        SProduct(
          SObjectInfo(description),
          List(
            FieldName("detail") -> Schema.schemaForString
          ) ++ fields
        )
      )
    }

    implicit lazy val schema: Schema[E] = apiErrorSchema(List.empty)
  }

  final case class Unauthorized(detail: String) extends ApiError[StatusCode.Unauthorized.type]

  object Unauthorized
      extends Companion[StatusCode.Unauthorized.type, Unauthorized](StatusCode.Unauthorized) {
    val decoder = deriveDecoder[Unauthorized]
  }

  final case class BadRequest(detail: String) extends ApiError[StatusCode.BadRequest.type]

  object BadRequest
      extends Companion[StatusCode.BadRequest.type, BadRequest](StatusCode.BadRequest) {
    val decoder = deriveDecoder[BadRequest]
  }

  final case class ServiceUnavailable(detail: String)
      extends ApiError[StatusCode.ServiceUnavailable.type] {}

  object ServiceUnavailable
      extends Companion[StatusCode.ServiceUnavailable.type, ServiceUnavailable](
        StatusCode.ServiceUnavailable
      ) {
    val decoder = deriveDecoder[ServiceUnavailable]
  }

  final case class InternalServerError(detail: String)
      extends ApiError[StatusCode.InternalServerError.type]

  object InternalServerError
      extends Companion[StatusCode.InternalServerError.type, InternalServerError](
        StatusCode.InternalServerError
      ) {
    val decoder = deriveDecoder[InternalServerError]
  }

  final case class NotFound(resource: String) extends ApiError[StatusCode.NotFound.type] {
    final val detail: String = s"$resource not found"
  }

  object NotFound extends Companion[StatusCode.NotFound.type, NotFound](StatusCode.NotFound) {
    val decoder                  = deriveDecoder[NotFound]
    override val specificEncoder = Some(deriveEncoder[NotFound])

    implicit override lazy val schema: Schema[NotFound] = apiErrorSchema(
      List(FieldName("resource") -> Schema.schemaForInt)
    )
  }

}
