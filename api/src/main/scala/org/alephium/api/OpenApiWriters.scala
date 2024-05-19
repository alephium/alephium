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

import sttp.apispec._
import sttp.apispec.openapi._

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
    case Left(Reference(ref, summaryOpt, descriptionOpt)) =>
      val summary = summaryOpt
        .map(summary => LinkedHashMap(("summary", ujson.Str(summary))))
        .getOrElse(LinkedHashMap.empty)
      val description = descriptionOpt
        .map(description => LinkedHashMap(("description", ujson.Str(description))))
        .getOrElse(LinkedHashMap.empty)
      val value: LinkedHashMap[String, ujson.Value] =
        LinkedHashMap((s"$$ref", ujson.Str(ref))) ++ summary ++ description
      ujson.Obj.from(value)
    case Right(t) => writeJs(t)
  }

  implicit val encoderMultipleExampleValue: Writer[ExampleMultipleValue] =
    writer[ujson.Value].comap { e =>
      ujson.Arr(e.values.map(v => writeJs(ExampleSingleValue(v))): _*)
    }

  def encodeExampleValue(alwaysArray: Boolean): Writer[ExampleValue] = {
    writer[ujson.Value].comap {
      case e: ExampleMultipleValue => writeJs(e)
      case e: ExampleSingleValue =>
        if (alwaysArray) {
          writeJs(ExampleMultipleValue(List(e.value)))
        } else {
          writeJs(e)
        }
    }
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
  implicit val encoderExampleValue: Writer[ExampleValue] = encodeExampleValue(false)

  implicit val writerAnySchema: Writer[AnySchema] = writer[ujson.Value].comap(_ => ujson.Bool(true))
  implicit val writerSchemaType: Writer[SchemaType] = writer[ujson.Value].comap(_.value)
  implicit val writerSchema: Writer[Schema] = expandExtensions(writer[ujson.Value].comap { schema =>
    // keep the same order as the definition:
    // https://github.com/softwaremill/sttp-apispec/blob/master/apispec-model/src/main/scala/sttp/apispec/Schema.scala#L28
    ujson.Obj(
      (s"$$schema", writeJs(schema.$schema)),
      (s"$$vocabulary", writeJs(schema.$vocabulary)),
      (s"$$id", writeJs(schema.$id)),
      (s"$$anchor", writeJs(schema.$anchor)),
      (s"$$dynamicAnchor", writeJs(schema.$dynamicAnchor)),
      (s"$$ref", writeJs(schema.$ref)),
      (s"$$dynamicRef", writeJs(schema.$dynamicRef)),
      (s"$$comment", writeJs(schema.$comment)),
      (s"$$defs", writeJs(schema.$defs)),
      ("title", writeJs(schema.title)),
      ("description", writeJs(schema.description)),
      ("default", writeJs(schema.default.map(writeJs(_)(encodeExampleValue(false))))),
      ("deprecated", writeJs(schema.deprecated)),
      ("readOnly", writeJs(schema.readOnly)),
      ("writeOnly", writeJs(schema.writeOnly)),
      (
        "examples",
        schema.examples.map(writeJs(_)(writerList(encodeExampleValue(true)))).getOrElse(ujson.Null)
      ),
      (
        "type",
        schema.`type` match {
          case Some(List(tpe)) => writeJs(tpe)
          case Some(list)      => writeJs(list)
          case None            => ujson.Null
        }
      ),
      ("enum", writeJs(schema.`enum`)),
      ("const", writeJs(schema.const.map(writeJs(_)(encodeExampleValue(false))))),
      ("format", writeJs(schema.format)),
      ("allOf", writeJs(schema.allOf)),
      ("anyOf", writeJs(schema.anyOf)),
      ("oneOf", writeJs(schema.oneOf)),
      ("not", writeJs(schema.not)),
      ("if", writeJs(schema.`if`)),
      ("then", writeJs(schema.`then`)),
      ("else", writeJs(schema.`else`)),
      ("dependentSchemas", writeJs(schema.dependentSchemas)),
      ("multipleOf", writeJs(schema.multipleOf)),
      ("minimum", writeJs(schema.minimum)),
      ("exclusiveMinimum", writeJs(schema.exclusiveMinimum)),
      ("maximum", writeJs(schema.maximum)),
      ("exclusiveMaximum", writeJs(schema.exclusiveMaximum)),
      ("maxLength", writeJs(schema.maxLength)),
      ("minLength", writeJs(schema.minLength)),
      ("pattern", writeJs(schema.pattern)),
      ("maxItems", writeJs(schema.maxItems)),
      ("minItems", writeJs(schema.minItems)),
      ("uniqueItems", writeJs(schema.uniqueItems)),
      ("maxContains", writeJs(schema.maxContains)),
      ("minContains", writeJs(schema.minContains)),
      ("prefixItems", writeJs(schema.prefixItems)),
      ("items", writeJs(schema.items)),
      ("contains", writeJs(schema.contains)),
      ("unevaluatedItems", writeJs(schema.unevaluatedItems)),
      ("maxProperties", writeJs(schema.maxProperties)),
      ("minProperties", writeJs(schema.minProperties)),
      ("required", writeJs(schema.required)),
      ("dependentRequired", writeJs(schema.dependentRequired)),
      ("discriminator", writeJs(schema.discriminator)),
      ("properties", writeJs(schema.properties)),
      (
        "patternProperties",
        if (schema.patternProperties.nonEmpty) writeJs(schema.patternProperties) else ujson.Null
      ),
      ("additionalProperties", writeJs(schema.additionalProperties)),
      ("propertyNames", writeJs(schema.propertyNames)),
      ("unevaluatedProperties", writeJs(schema.unevaluatedProperties)),
      ("externalDocs", writeJs(schema.externalDocs)),
      ("extensions", writeJs(schema.extensions))
    )
  })
  implicit def writerSchemaLike: Writer[SchemaLike] = expandExtensions(
    writer[ujson.Value].comap {
      case s: AnySchema => writeJs(s)
      case s: Schema    => writeJs(s)
    }
  )
  implicit val writerReference: Writer[Reference]           = macroW[Reference]
  implicit val writerPattern: Writer[Pattern]               = StringWriter.comap(_.value)
  implicit val writerHeader: Writer[Header]                 = macroW[Header]
  implicit val writerExample: Writer[Example]               = expandExtensions(macroW[Example])
  implicit val writerLink: Writer[Link]                     = expandExtensions(macroW[Link])
  implicit val writerCallback: Writer[Callback]             = expandExtensions(macroW[Callback])
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
          case (ResponsesDefaultKey, r)      => ("default", writeJs(r))
          case (ResponsesCodeKey(code), r)   => (code.toString, writeJs(r))
          case (ResponsesRangeKey(range), r) => (range.toString, writeJs(r))
        }
        ujson.Obj.from(fields.toSeq)
      }
    }
  implicit val writerResponses: Writer[Responses] = writer[ujson.Value].comap { resp =>
    val extensions = writeJs(resp.extensions).objOpt.getOrElse(LinkedHashMap.empty)
    val respJson   = writeJs(resp.responses)
    respJson.objOpt.map(p => ujson.Obj.from(p ++ extensions)).getOrElse(respJson)
  }
  implicit val writerOperation: Writer[Operation] = expandExtensions(macroW[Operation])
  implicit val writerPathItem: Writer[PathItem]   = macroW[PathItem]
  implicit val writerPaths: Writer[Paths] = writer[ujson.Value].comap { paths =>
    val extensions = writeJs(paths.extensions).objOpt.getOrElse(LinkedHashMap.empty)
    val pathItems  = writeJs(paths.pathItems)
    pathItems.objOpt.map(p => ujson.Obj.from(p ++ extensions)).getOrElse(pathItems)
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
    ujson.Obj.from(extensions.objOpt.map(ext => ext ++ jsonWithoutExt).getOrElse(jsonWithoutExt))
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
