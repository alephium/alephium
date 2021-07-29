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

import scala.annotation.nowarn

import sttp.model.StatusCode
import sttp.tapir.{FieldName, Schema}
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.{SProduct, SProductField}

import org.alephium.json.Json._

sealed trait ApiError[S <: StatusCode] {
  def detail: String
}

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
object ApiError {

  sealed abstract class Companion[S <: StatusCode, E <: ApiError[S]](val statusCode: S) {
    def description: String = this.getClass.getSimpleName.dropRight(1)

    @nowarn
    def specificFields(e: E): Option[ujson.Obj] = {
      None
    }

    lazy val writerE: Writer[E] = {
      writer[ujson.Value].comap { apiError =>
        val base = "detail" -> ujson.Str(apiError.detail)
        specificFields(apiError) match {
          case None         => ujson.Obj(base)
          case Some(fields) => ujson.Obj(fields.value.addOne(base))
        }
      }
    }

    def apply(detail: String): E

    def readerE: Reader[E]

    implicit lazy val readWriter: ReadWriter[E] = {
      ReadWriter.join(readerE, writerE)
    }

    def apiErrorSchema(
        fields: List[SProductField[E]]
    ): Schema[E] = {
      Schema(
        SProduct(
          List(
            SProductField[E, String](
              FieldName("detail"),
              Schema.schemaForString,
              error => Some(error.detail)
            )
          ) ++ fields
        ),
        Some(SName(description))
      )
    }

    implicit lazy val schema: Schema[E] = apiErrorSchema(List.empty)
  }

  final case class Unauthorized(detail: String) extends ApiError[StatusCode.Unauthorized.type]

  object Unauthorized
      extends Companion[StatusCode.Unauthorized.type, Unauthorized](StatusCode.Unauthorized) {
    def readerE: Reader[Unauthorized] =
      reader[ujson.Value].map(json => Unauthorized(read[String](json("detail"))))
  }

  final case class BadRequest(detail: String) extends ApiError[StatusCode.BadRequest.type]

  object BadRequest
      extends Companion[StatusCode.BadRequest.type, BadRequest](StatusCode.BadRequest) {
    def readerE: Reader[BadRequest] =
      reader[ujson.Value].map(json => BadRequest(read[String](json("detail"))))
  }

  final case class ServiceUnavailable(detail: String)
      extends ApiError[StatusCode.ServiceUnavailable.type] {}

  object ServiceUnavailable
      extends Companion[StatusCode.ServiceUnavailable.type, ServiceUnavailable](
        StatusCode.ServiceUnavailable
      ) {
    def readerE: Reader[ServiceUnavailable] =
      reader[ujson.Value].map(json => ServiceUnavailable(read[String](json("detail"))))
  }

  final case class InternalServerError(detail: String)
      extends ApiError[StatusCode.InternalServerError.type]

  object InternalServerError
      extends Companion[StatusCode.InternalServerError.type, InternalServerError](
        StatusCode.InternalServerError
      ) {
    def readerE: Reader[InternalServerError] =
      reader[ujson.Value].map(json => InternalServerError(read[String](json("detail"))))
  }

  final case class NotFound(resource: String) extends ApiError[StatusCode.NotFound.type] {
    final val detail: String = s"$resource not found"
  }

  object NotFound extends Companion[StatusCode.NotFound.type, NotFound](StatusCode.NotFound) {
    def readerE: Reader[NotFound] =
      reader[ujson.Value].map(json => NotFound(read[String](json("resource"))))
    override def specificFields(notFound: NotFound): Option[ujson.Obj] = Some(
      ujson.Obj(
        "resource" -> ujson.Str(notFound.resource)
      )
    )

    implicit override lazy val schema: Schema[NotFound] = apiErrorSchema(
      List(
        SProductField[NotFound, String](
          FieldName("resource"),
          Schema.schemaForString,
          notFound => Some(notFound.resource)
        )
      )
    )
  }

}
