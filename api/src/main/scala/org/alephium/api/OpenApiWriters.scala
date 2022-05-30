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
import scala.collection.mutable.LinkedHashMap
import scala.util.Try

import sttp.tapir.apispec._
import sttp.tapir.openapi._

import org.alephium.json.Json._

object OpenAPIWriters extends EndpointsExamples {

  def openApiJson(openAPI: OpenAPI, dropAuth: Boolean): String = {
    val newOpenAPI = if (dropAuth) {
      dropSecurityFields(openAPI)
    } else {
      openAPI
    }
    cleanOpenAPIResult(
      write(
        dropNullValues(writeJs(newOpenAPI)),
        indent = 2
      )
    )
  }

  private def cleanOpenAPIResult(openAPI: String): String = {
    openAPI.replaceAll(address.toBase58, address.toBase58.dropRight(2))
  }

  def dropSecurityFields(openAPI: OpenAPI): OpenAPI = {
    val components: Option[Components] =
      openAPI.components.map(_.copy(securitySchemes = ListMap.empty))
    val paths: Paths = openAPI.paths.copy(pathItems = openAPI.paths.pathItems.map {
      case (key, pathItem) =>
        (key, mapOperation(pathItem)(_.copy(security = List.empty)))
    })
    openAPI.copy(components = components, paths = paths)
  }

  private def mapOperation(pathItem: PathItem)(f: Operation => Operation): PathItem = {
    pathItem.copy(
      get = pathItem.get.map(f),
      put = pathItem.put.map(f),
      post = pathItem.post.map(f),
      delete = pathItem.delete.map(f),
      options = pathItem.options.map(f),
      head = pathItem.head.map(f),
      patch = pathItem.patch.map(f),
      trace = pathItem.trace.map(f)
    )
  }

  // needed because `OpenAPI.openapi` got a default value in tapir and upickle doesnt serialize it for weird reason
  final case class MyOpenAPI(
      openapi: String,
      info: Info,
      tags: List[Tag],
      servers: List[Server],
      paths: Paths,
      components: Option[Components],
      security: List[SecurityRequirement],
      extensions: ListMap[String, ExtensionValue]
  )

  implicit def writerReferenceOr[T: Writer]: Writer[ReferenceOr[T]] = writer[ujson.Value].comap {
    case Left(Reference(ref)) => ujson.Obj((s"$$ref", ujson.Str(ref)))
    case Right(t)             => writeJs(t)
  }

  implicit val extensionValue: Writer[ExtensionValue] = writer[ujson.Value].comap {
    case ExtensionValue(value) =>
      Try(read[ujson.Value](value)).toEither.toOption.getOrElse(ujson.Str(value))
  }
  implicit val writerOAuthFlow: Writer[OAuthFlow]   = expandExtensions(macroW[OAuthFlow])
  implicit val writerOAuthFlows: Writer[OAuthFlows] = expandExtensions(macroW[OAuthFlows])
  implicit val writerSecurityScheme: Writer[SecurityScheme] = expandExtensions(
    macroW[SecurityScheme]
  )
  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  implicit val writerExampleValue: Writer[ExampleSingleValue] = writer[ujson.Value].comap {
    case ExampleSingleValue(value: String) =>
      Try(read[ujson.Value](value)).toEither.toOption.getOrElse(ujson.Str(value))
    case ExampleSingleValue(value: Int)        => writeJs(value)
    case ExampleSingleValue(value: Long)       => writeJs(value)
    case ExampleSingleValue(value: Float)      => writeJs(value)
    case ExampleSingleValue(value: Double)     => writeJs(value)
    case ExampleSingleValue(value: Boolean)    => writeJs(value)
    case ExampleSingleValue(value: BigDecimal) => writeJs(value)
    case ExampleSingleValue(value: BigInt)     => writeJs(value)
    // scalastyle:off null
    case ExampleSingleValue(null) => ujson.Null
    // scalastyle:on null
    case ExampleSingleValue(value) => ujson.Str(value.toString)
  }
  implicit val encoderExampleValue: Writer[ExampleValue] = writer[ujson.Value].comap {
    case e: ExampleSingleValue => writeJs[ExampleSingleValue](e)
    case ExampleMultipleValue(values) =>
      ujson.Arr(
        values.map(e => writeJs[ExampleSingleValue](ExampleSingleValue(e)))
      )
  }
  implicit val writerSchemaType: Writer[SchemaType]         = StringWriter.comap(_.value)
  implicit val writerSchema: Writer[Schema]                 = expandExtensions(macroW[Schema])
  implicit val writerReference: Writer[Reference]           = macroW[Reference]
  implicit val writerHeader: Writer[Header]                 = macroW[Header]
  implicit val writerExample: Writer[Example]               = expandExtensions(macroW[Example])
  implicit val writerResponse: Writer[Response]             = expandExtensions(macroW[Response])
  implicit val writerEncoding: Writer[Encoding]             = expandExtensions(macroW[Encoding])
  implicit val writerMediaType: Writer[MediaType]           = expandExtensions(macroW[MediaType])
  implicit val writerRequestBody: Writer[RequestBody]       = expandExtensions(macroW[RequestBody])
  implicit val writerParameterStyle: Writer[ParameterStyle] = StringWriter.comap(_.value)
  implicit val writerParameterIn: Writer[ParameterIn]       = StringWriter.comap(_.value)
  implicit val writerParameter: Writer[Parameter]           = expandExtensions(macroW[Parameter])
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
  implicit val writerResponses: Writer[Responses] = writer[ujson.Value].comap { resp =>
    val extensions = writeJs(resp.extensions).objOpt.getOrElse(LinkedHashMap.empty)
    val respJson   = writeJs(resp.responses)
    respJson.objOpt.map(p => ujson.Obj(p ++ extensions)).getOrElse(respJson)
  }
  implicit val writerOperation: Writer[Operation] = expandExtensions(macroW[Operation])
  implicit val writerPathItem: Writer[PathItem]   = macroW[PathItem]
  implicit val writerPaths: Writer[Paths] = writer[ujson.Value].comap { paths =>
    val extensions = writeJs(paths.extensions).objOpt.getOrElse(LinkedHashMap.empty)
    val pathItems  = writeJs(paths.pathItems)
    pathItems.objOpt.map(p => ujson.Obj(p ++ extensions)).getOrElse(pathItems)
  }
  implicit val writerComponents: Writer[Components] = expandExtensions(macroW[Components])
  implicit val writerServerVariable: Writer[ServerVariable] = expandExtensions(
    macroW[ServerVariable]
  )
  implicit val writerServer: Writer[Server] = expandExtensions(macroW[Server])
  implicit val writerExternalDocumentation: Writer[ExternalDocumentation] =
    expandExtensions(macroW[ExternalDocumentation])
  implicit val writerTag: Writer[Tag]                     = expandExtensions(macroW[Tag])
  implicit val writerInfo: Writer[Info]                   = expandExtensions(macroW[Info])
  implicit val writerContact: Writer[Contact]             = expandExtensions(macroW[Contact])
  implicit val writerLicense: Writer[License]             = expandExtensions(macroW[License])
  implicit private val writerMyOpenAPI: Writer[MyOpenAPI] = expandExtensions(macroW[MyOpenAPI])
  implicit val writerDiscriminator: Writer[Discriminator] = macroW[Discriminator]

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

  private def expandExtensions[T](writerT: Writer[T]): Writer[T] = {
    writer[ujson.Value].comap { c =>
      val v = writeJs(c)(writerT)
      v.objOpt.map(obj => expandObjExtensions(ujson.Obj(obj))).getOrElse(v)
    }

  }

  private def expandObjExtensions(jsonObject: ujson.Obj): ujson.Obj = {
    val extensions = ujson.Obj.from(jsonObject.value.find { case (key, _) => key == "extensions" })
    val jsonWithoutExt = jsonObject.value.filter { case (key, _) => key != "extensions" }
    ujson.Obj(extensions.objOpt.map(ext => ext ++ jsonWithoutExt).getOrElse(jsonWithoutExt))
  }

  implicit private val openapiWriter: Writer[OpenAPI] = writerMyOpenAPI.comap[OpenAPI] { openapi =>
    MyOpenAPI(
      openapi.openapi,
      openapi.info,
      openapi.tags,
      openapi.servers,
      openapi.paths,
      openapi.components,
      openapi.security,
      openapi.extensions
    )
  }

}
