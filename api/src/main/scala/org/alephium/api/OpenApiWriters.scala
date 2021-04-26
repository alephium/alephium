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

import scala.collection.immutable.ListMap
import scala.util.Try

import sttp.tapir.apispec._
import sttp.tapir.openapi._

import org.alephium.json.Json._

object OpenAPIWriters {

  def openApiJson(openAPI: OpenAPI): String = write(
    dropNullValues(writeJs(openAPI)),
    indent = 2
  )

  //needed because `OpenAPI.openapi` got a default value in tapir and upickle doesnt serialize it for weird reason
  final private case class MyOpenAPI(
      openapi: String,
      info: Info,
      tags: List[Tag],
      servers: List[Server],
      paths: ListMap[String, PathItem],
      components: Option[Components],
      security: List[SecurityRequirement]
  )

  implicit def writerReferenceOr[T: Writer]: Writer[ReferenceOr[T]] = writer[ujson.Value].comap {
    case Left(Reference(ref)) => ujson.Obj((s"$$ref", ujson.Str(ref)))
    case Right(t)             => writeJs(t)
  }

  implicit val writerOAuthFlow: Writer[OAuthFlow]           = macroW[OAuthFlow]
  implicit val writerOAuthFlows: Writer[OAuthFlows]         = macroW[OAuthFlows]
  implicit val writerSecurityScheme: Writer[SecurityScheme] = macroW[SecurityScheme]
  implicit val writerExampleValue: Writer[ExampleValue] = writer[ujson.Value].comap {
    case ExampleSingleValue(value) =>
      Try(read[ujson.Value](value)).toEither.toOption.getOrElse(ujson.Str(value))
    case ExampleMultipleValue(values) =>
      ujson.Arr(
        values.map(v => Try(read[ujson.Value](v)).toEither.toOption.getOrElse(ujson.Str(v)))
      )
  }
  implicit val writerSchemaType: Writer[SchemaType.SchemaType]             = writeEnumeration
  implicit val writerSchema: Writer[Schema]                                = macroW[Schema]
  implicit val writerReference: Writer[Reference]                          = macroW[Reference]
  implicit val writerHeader: Writer[Header]                                = macroW[Header]
  implicit val writerExample: Writer[Example]                              = macroW[Example]
  implicit val writerResponse: Writer[Response]                            = macroW[Response]
  implicit val writerEncoding: Writer[Encoding]                            = macroW[Encoding]
  implicit val writerMediaType: Writer[MediaType]                          = macroW[MediaType]
  implicit val writerRequestBody: Writer[RequestBody]                      = macroW[RequestBody]
  implicit val writerParameterStyle: Writer[ParameterStyle.ParameterStyle] = writeEnumeration
  implicit val writerParameterIn: Writer[ParameterIn.ParameterIn]          = writeEnumeration
  implicit val writerParameter: Writer[Parameter]                          = macroW[Parameter]
  implicit val writerResponseMap: Writer[ListMap[ResponsesKey, ReferenceOr[Response]]] =
    writer[ujson.Value].comap { (responses: ListMap[ResponsesKey, ReferenceOr[Response]]) =>
      {
        val fields = responses.map {
          case (ResponsesDefaultKey, r)    => ("default", writeJs(r))
          case (ResponsesCodeKey(code), r) => (code.toString, writeJs(r))
        }
        ujson.Obj.from(fields.toSeq)
      }
    }
  implicit val writerOperation: Writer[Operation]           = macroW[Operation]
  implicit val writerPathItem: Writer[PathItem]             = macroW[PathItem]
  implicit val writerComponents: Writer[Components]         = macroW[Components]
  implicit val writerServerVariable: Writer[ServerVariable] = macroW[ServerVariable]
  implicit val writerServer: Writer[Server]                 = macroW[Server]
  implicit val writerExternalDocumentation: Writer[ExternalDocumentation] =
    macroW[ExternalDocumentation]
  implicit val writerTag: Writer[Tag]                     = macroW[Tag]
  implicit val writerInfo: Writer[Info]                   = macroW[Info]
  implicit val writerContact: Writer[Contact]             = macroW[Contact]
  implicit val writerLicense: Writer[License]             = macroW[License]
  implicit val writerDiscriminator: Writer[Discriminator] = macroW[Discriminator]

  implicit private val writerMyOpenAPI: Writer[MyOpenAPI] = macroW[MyOpenAPI]

  implicit def writerList[T: Writer]: Writer[List[T]] = writer[ujson.Value].comap {
    case Nil        => ujson.Null
    case l: List[T] => ujson.Arr.from(l.map(writeJs(_)))
  }

  private def doWriterListMap[V: Writer](nullWhenEmpty: Boolean): Writer[ListMap[String, V]] =
    writer[ujson.Value].comap {
      case m: ListMap[String, V] if m.isEmpty && nullWhenEmpty => ujson.Null
      case m: ListMap[String, V] =>
        val properties = m.view.mapValues(writeJs(_))
        ujson.Obj.from(properties)
    }
  implicit def writeListMap[V: Writer]: Writer[ListMap[String, V]] =
    doWriterListMap(nullWhenEmpty = true)

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  final def writeEnumeration[E <: Enumeration]: Writer[E#Value] = StringWriter.comap(_.toString)

  implicit private val openapiWriter: Writer[OpenAPI] = writerMyOpenAPI.comap[OpenAPI] { openapi =>
    MyOpenAPI(
      openapi.openapi,
      openapi.info,
      openapi.tags,
      openapi.servers,
      openapi.paths,
      openapi.components,
      openapi.security
    )
  }

}
