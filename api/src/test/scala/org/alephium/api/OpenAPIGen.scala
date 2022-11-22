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

import org.scalacheck.Arbitrary.arbitrary
import sttp.apispec._
import sttp.apispec.openapi._

import org.scalacheck.Gen

object OpenAPIGen {

  def listMapGen[A,B](keyGen:Gen[A], valueGen:Gen[B]):Gen[ListMap[A,B]]=
  for {
    listMap <- Gen.listOf(Gen.zip(keyGen,valueGen))
  } yield ListMap.from(listMap)

  def mapGen[A,B](keyGen:Gen[A], valueGen:Gen[B]):Gen[Map[A,B]]=
    Gen.mapOf(Gen.zip(keyGen,valueGen))


  def referenceGen : Gen[Reference] =
  for {
    ref <- Gen.alphaNumStr
    summary <- Gen.option(Gen.alphaNumStr)
    description <- Gen.option(Gen.alphaNumStr)
  } yield Reference(ref,summary, description)

  def referenceOrGen[A](or:Gen[A]) :Gen[ReferenceOr[A]] =
    Gen.either(referenceGen, or)

  def schemaLikeGen: Gen[SchemaLike]= Gen.oneOf(
    Gen.const(AnySchema.Anything),
    Gen.const(AnySchema.Nothing),
    schemaGen
    )
  def arraySchemaTypeGen:Gen[ArraySchemaType] =
    for{
    list<- Gen.listOf(basicSchemaTypeGen)
    } yield ArraySchemaType(list)

def basicSchemaTypeGen:Gen[BasicSchemaType] = Gen.oneOf(
  SchemaType.Boolean,
  SchemaType.Object,
  SchemaType.Array,
  SchemaType.Number,
  SchemaType.String,
  SchemaType.Integer,
  SchemaType.Null
  )

def schemaTypeGen:Gen[SchemaType] = Gen.oneOf(
  basicSchemaTypeGen,
  arraySchemaTypeGen
  )

def patternGen:Gen[Pattern] = for {
  value <- Gen.alphaNumStr
} yield Pattern(value)

def anyGen:Gen[Any] = Gen.oneOf(
  Gen.alphaNumStr,
  arbitrary[Int],
  arbitrary[Long],
  arbitrary[Float],
  arbitrary[Double],
  arbitrary[Boolean],
  arbitrary[BigDecimal],
  arbitrary[BigInt],
  Gen.const(null),
  Gen.const('c')
  )

def exampleSingleValueGen:Gen[ExampleSingleValue] = for{
  value <- anyGen
} yield ExampleSingleValue(value)

def exampleMultipleValueGen:Gen[ExampleMultipleValue] = for{
  values <- Gen.listOf(anyGen)
} yield ExampleMultipleValue(values)

def exampleValueGen:Gen[ExampleValue] =Gen.oneOf(
  exampleSingleValueGen,
  exampleMultipleValueGen
  )
def discriminatorGen: Gen[Discriminator] = for {
  propertyName <- Gen.alphaNumStr
  mapping <- Gen.option(listMapGen(Gen.alphaNumStr, Gen.alphaNumStr))
}yield Discriminator(propertyName, mapping)

def extensionValueGen: Gen[ExtensionValue] = for {
value<- Gen.alphaNumStr
} yield ExtensionValue(value)

  def schemaGen: Gen[Schema] =
    for{
    schema<- Gen.option(Gen.alphaNumStr)
    allOf<-Gen.listOf(referenceOrGen(schemaLikeGen))
    title<- Gen.option(Gen.alphaNumStr)
    required<-Gen.listOf(Gen.alphaNumStr)
    `type`<- Gen.option(schemaTypeGen)
    prefixItems<- Gen.option(Gen.listOf(referenceOrGen(schemaLikeGen)))
    items<- Gen.option(referenceOrGen(schemaLikeGen))
    contains<- Gen.option(referenceOrGen(schemaLikeGen))
    properties<- listMapGen(Gen.alphaNumStr, referenceOrGen(schemaLikeGen))
    patternProperties<- listMapGen(patternGen, referenceOrGen(schemaLikeGen))
    description<- Gen.option(Gen.alphaNumStr)
    format<- Gen.option(Gen.alphaNumStr)
    default<- Gen.option(exampleValueGen)
    nullable<- Gen.option(arbitrary[Boolean])
    readOnly<- Gen.option(arbitrary[Boolean])
    writeOnly<- Gen.option(arbitrary[Boolean])
    example<- Gen.option(exampleValueGen)
    deprecated<- Gen.option(arbitrary[Boolean])
    oneOf <- Gen.listOf(referenceOrGen(schemaLikeGen))
    discriminator<- Gen.option(discriminatorGen)
    additionalProperties<- Gen.option(referenceOrGen(schemaLikeGen))
    pattern<- Gen.option(patternGen)
    minLength<- Gen.option(arbitrary[Int])
    maxLength<- Gen.option(arbitrary[Int])
    minimum<- Gen.option(arbitrary[BigDecimal])
    exclusiveMinimum<- Gen.option(arbitrary[Boolean])
    maximum<- Gen.option(arbitrary[BigDecimal])
    exclusiveMaximum<- Gen.option(arbitrary[Boolean])
    minItems<- Gen.option(arbitrary[Int])
    maxItems<- Gen.option(arbitrary[Int])
    `enum`<- Gen.option(Gen.listOf(exampleSingleValueGen))
    not<- Gen.option(referenceOrGen(schemaLikeGen))
    `if`<- Gen.option(referenceOrGen(schemaLikeGen))
    `then`<- Gen.option(referenceOrGen(schemaLikeGen))
    `else`<- Gen.option(referenceOrGen(schemaLikeGen))
    defs<- Gen.option(listMapGen(Gen.alphaNumStr,schemaLikeGen))
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
    } yield {
Schema(
    schema,
    allOf,
    title,
    required,
    `type`,
    prefixItems,
    items,
    contains,
    properties,
    patternProperties,
    description,
    format,
    default,
    nullable,
    readOnly,
    writeOnly,
    example,
    deprecated,
    oneOf,
    discriminator,
    additionalProperties,
    pattern,
    minLength,
    maxLength,
    minimum,
    exclusiveMinimum,
    maximum,
    exclusiveMaximum,
    minItems,
    maxItems,
    `enum`,
    not,
    `if`,
    `then`,
    `else`,
    defs,
    extensions
)
    }

    def tagGen:Gen[Tag] = for {
      name  <-Gen.alphaNumStr
      description <- Gen.option(Gen.alphaNumStr)
      externalDocs<-Gen.option(externalDocumentationGen)
      extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
    } yield Tag(name,description, externalDocs, extensions)

    def externalDocumentationGen:Gen[ExternalDocumentation] = for {
      url <-Gen.alphaNumStr
      description <- Gen.option(Gen.alphaNumStr)
      extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
    } yield ExternalDocumentation(url,description, extensions)

    def serverGen:Gen[Server] = for {
    url<- Gen.alphaNumStr
    description<-Gen.option(Gen.alphaNumStr)
    variables<-  Gen.option(listMapGen(Gen.alphaNumStr, serverVariableGen))
      extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
    }yield Server(url, description, variables, extensions)

    def serverVariableGen: Gen[ServerVariable] = for {
    default<- Gen.alphaNumStr
    `enum`<-Gen.option(Gen.listOf(Gen.alphaNumStr).map(_:+default))
    description <-Gen.option(Gen.alphaNumStr)
      extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
    } yield ServerVariable(`enum`, default, description, extensions)

    def pathGen:Gen[Paths]= for {
      pathItems <- listMapGen(Gen.alphaNumStr, pathItemGen)
      extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
    }yield Paths(pathItems, extensions)

    def pathItemGen:Gen[PathItem] = for {
    ref<- Gen.option(referenceGen)
    summary<- Gen.option(Gen.alphaNumStr)
    description<- Gen.option(Gen.alphaNumStr)
    get<- Gen.option(operationGen)
    put<- Gen.option(operationGen)
    post<- Gen.option(operationGen)
    delete<- Gen.option(operationGen)
    options<- Gen.option(operationGen)
    head<- Gen.option(operationGen)
    patch<- Gen.option(operationGen)
    trace<- Gen.option(operationGen)
    servers<- Gen.listOf(serverGen)
    parameters<- Gen.listOf(referenceOrGen(parameterGen))
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)

    } yield PathItem(
    ref,
    summary,
    description,
    get,
    put,
    post,
    delete,
    options,
    head,
    patch,
    trace,
    servers,
    parameters,
    extensions
      )


def operationGen:Gen[Operation] = for {
    tags<- Gen.listOf(Gen.alphaNumStr)
    summary<- Gen.option(Gen.alphaNumStr)
    description<- Gen.option(Gen.alphaNumStr)
    externalDocs<- Gen.option(externalDocumentationGen)
    operationId<- Gen.option(Gen.alphaNumStr)
    parameters<- Gen.listOf(referenceOrGen(parameterGen))
    requestBody<- Gen.option(referenceOrGen(requestBodyGen))
    responses<- responsesGen
    callbacks<- listMapGen(Gen.alphaNumStr, referenceOrGen(callbackGen))
    deprecated<- Gen.option(arbitrary[Boolean])
    security<- Gen.listOf(securityRequirementGen)
    servers<- Gen.listOf(serverGen)
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
} yield Operation(
    tags,
    summary,
    description,
    externalDocs,
    operationId,
    parameters,
    requestBody,
    responses,
    callbacks,
    deprecated,
    security,
    servers,
    extensions
  )

def parameterGen:Gen[Parameter] = for {
    name<- Gen.alphaNumStr
    in<- parameterInGen
    description<- Gen.option(Gen.alphaNumStr)
    required<- Gen.option(arbitrary[Boolean])
    deprecated<- Gen.option(arbitrary[Boolean])
    allowEmptyValue<- Gen.option(arbitrary[Boolean])
    style<- Gen.option(parameterStyleGen)
    explode<- Gen.option(arbitrary[Boolean])
    allowReserved<- Gen.option(arbitrary[Boolean])
    schema<- Gen.option(referenceOrGen(schemaLikeGen))
    example<- Gen.option(exampleValueGen)
    examples<- listMapGen(Gen.alphaNumStr, referenceOrGen(exampleGen) )
    content<- listMapGen(Gen.alphaNumStr, mediaTypeGen )
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Parameter(
    name,
    in,
    description,
    required,
    deprecated,
    allowEmptyValue,
    style,
    explode,
    allowReserved,
    schema,
    example,
    examples,
    content,
    extensions
  )

def requestBodyGen:Gen[RequestBody] = for {
    description<- Gen.option(Gen.alphaNumStr)
    content <- listMapGen(Gen.alphaNumStr, mediaTypeGen)
    required <- Gen.option(arbitrary[Boolean])
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield RequestBody(description, content, required, extensions)

def responsesGen:Gen[Responses] = for {
    responses<- listMapGen(responsesKeyGen, referenceOrGen(responseGen))
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Responses(responses, extensions)

def callbackGen: Gen[Callback] = for {
      pathItems <- listMapGen(Gen.alphaNumStr, referenceOrGen(pathItemGen))
}yield Callback(pathItems)

def securityRequirementGen: Gen[SecurityRequirement] =
  listMapGen(Gen.alphaNumStr, Gen.listOf(Gen.alphaNumStr).map(Vector.from))

def parameterInGen:Gen[ParameterIn] = Gen.oneOf(
  ParameterIn.Query,
  ParameterIn.Header,
  ParameterIn.Path,
  ParameterIn.Cookie
  )

def parameterStyleGen:Gen[ParameterStyle] = Gen.oneOf(
  ParameterStyle.Simple,
  ParameterStyle.Form,
  ParameterStyle.Matrix,
  ParameterStyle.Label,
  ParameterStyle.SpaceDelimited,
  ParameterStyle.PipeDelimited,
  ParameterStyle.DeepObject
  )

def exampleGen:Gen[Example] = for {
    summary<- Gen.option(Gen.alphaNumStr)
    description<- Gen.option(Gen.alphaNumStr)
  value<-Gen.option(exampleValueGen)
    externalValue<- Gen.option(Gen.alphaNumStr)
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Example(
    summary,
      description,
    value,
    externalValue,
    extensions,
  )

def mediaTypeGen:Gen[MediaType] = for {
    schema<-Gen.option(referenceOrGen(schemaLikeGen))
    example<-Gen.option(exampleValueGen)
    examples<-listMapGen(Gen.alphaNumStr, referenceOrGen(exampleGen))
    encoding<-listMapGen(Gen.alphaNumStr, encodingGen)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield MediaType(
    schema,
    example,
    examples,
    encoding,
    extensions
  )

def responsesKeyGen:Gen[ResponsesKey] = Gen.oneOf(
  arbitrary[Int].map(ResponsesCodeKey.apply),
  arbitrary[Int].map(ResponsesRangeKey.apply)
  )

def responseGen: Gen[Response] = for{
    description <- Gen.alphaNumStr
    headers<- listMapGen(Gen.alphaNumStr,referenceOrGen(headerGen))
    content <- listMapGen(Gen.alphaNumStr, mediaTypeGen)
    links<-listMapGen(Gen.alphaNumStr, referenceOrGen(linkGen))
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Response(
    description ,
    headers,
    content,
    links,
    extensions
  )

def encodingGen:Gen[Encoding] = for {
    contentType<-Gen.option(Gen.alphaNumStr)
    headers<-listMapGen(Gen.alphaNumStr, referenceOrGen(headerGen))
    style<-Gen.option(parameterStyleGen)
    explode<-Gen.option(arbitrary[Boolean])
    allowReserved<- Gen.option(arbitrary[Boolean])
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Encoding(
    contentType,
    headers,
    style,
    explode,
    allowReserved,
    extensions,
  )

def headerGen:Gen[Header] = for {
    description<- Gen.option(Gen.alphaNumStr)
    required<- Gen.option(arbitrary[Boolean])
    deprecated<- Gen.option(arbitrary[Boolean])
    allowEmptyValue<- Gen.option(arbitrary[Boolean])
    style<-Gen.option(parameterStyleGen)
    explode<- Gen.option(arbitrary[Boolean])
    allowReserved<- Gen.option(arbitrary[Boolean])
    schema<- Gen.option(referenceOrGen(schemaLikeGen))
    example<-Gen.option(exampleValueGen)
    examples<-listMapGen(Gen.alphaNumStr, referenceOrGen(exampleGen))
    content<-listMapGen(Gen.alphaNumStr, mediaTypeGen)
} yield Header(
    description,
    required,
    deprecated,
    allowEmptyValue,
    style,
    explode,
    allowReserved,
    schema,
    example,
    examples,
    content
  )
def linkGen:Gen[Link] = for {
    operationRef<-Gen.option(Gen.alphaNumStr)
    operationId<-Gen.option(Gen.alphaNumStr)
    parameters<-listMapGen(Gen.alphaNumStr, Gen.alphaNumStr)
    requestBody<-Gen.option(Gen.alphaNumStr)
    description<-Gen.option(Gen.alphaNumStr)
    server<-Gen.option(serverGen)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}  yield Link(
    operationRef,
    operationId,
    parameters,
    requestBody,
    description,
    server,
    extensions
  )

def infoGen:Gen[Info] =for{
    title<-Gen.alphaNumStr
    version<-Gen.alphaNumStr
    summary<-Gen.option(Gen.alphaNumStr)
    description<-Gen.option(Gen.alphaNumStr)
    termsOfService<-Gen.option(Gen.alphaNumStr)
    contact<-Gen.option(contactGen)
    license<-Gen.option(licenseGen)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Info(
    title,
    version,
    summary,
    description,
    termsOfService,
    contact,
    license,
    extensions
  )

    def contactGen : Gen[Contact] = for {
    name<-Gen.option(Gen.alphaNumStr)
    email<-Gen.option(Gen.alphaNumStr)
    url<-Gen.option(Gen.alphaNumStr)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
    } yield Contact(name,
      email,
      url,
      extensions
      )


def licenseGen:Gen[License] =for {
    name<-Gen.alphaNumStr
    url<-Gen.option(Gen.alphaNumStr)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield License(name,url, extensions)

def pathsGen:Gen[Paths] = for {
      pathItems <- listMapGen(Gen.alphaNumStr, pathItemGen)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
}yield Paths(
  pathItems,
  extensions
  )


def componentsGen:Gen[Components] = for {
    schemas<- listMapGen(Gen.alphaNumStr, referenceOrGen(schemaLikeGen))
    responses<- listMapGen(Gen.alphaNumStr, referenceOrGen(responseGen))
    parameters<- listMapGen(Gen.alphaNumStr, referenceOrGen(parameterGen))
    examples<- listMapGen(Gen.alphaNumStr, referenceOrGen(exampleGen))
    requestBodies<- listMapGen(Gen.alphaNumStr, referenceOrGen(requestBodyGen))
    headers<- listMapGen(Gen.alphaNumStr, referenceOrGen(headerGen))
    securitySchemes<- listMapGen(Gen.alphaNumStr, referenceOrGen(securitySchemeGen))
    links<- listMapGen(Gen.alphaNumStr, referenceOrGen(linkGen))
    callbacks<- listMapGen(Gen.alphaNumStr, referenceOrGen(callbackGen))
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
} yield Components(
    schemas,
    responses,
    parameters,
    examples,
    requestBodies,
    headers,
    securitySchemes,
    links,
    callbacks,
    extensions
  )


def securitySchemeGen:Gen[SecurityScheme] = for{
    `type`<-Gen.alphaNumStr
    description<-Gen.option(Gen.alphaNumStr)
    name<-Gen.option(Gen.alphaNumStr)
    in<-Gen.option(Gen.alphaNumStr)
    scheme<-Gen.option(Gen.alphaNumStr)
    bearerFormat<-Gen.option(Gen.alphaNumStr)
    flows<-Gen.option(oAuthFlowsGen)
    openIdConnectUrl<-Gen.option(Gen.alphaNumStr)
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
} yield SecurityScheme(
    `type`,
    description,
    name,
    in,
    scheme,
    bearerFormat,
    flows,
    openIdConnectUrl,
    extensions
  )

def oAuthFlowsGen:Gen[OAuthFlows] = for{
    `implicit`<-Gen.option(oAuthFlowGen)
    password<-Gen.option(oAuthFlowGen)
    clientCredentials<-Gen.option(oAuthFlowGen)
    authorizationCode<-Gen.option(oAuthFlowGen)
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
} yield OAuthFlows(
    `implicit`,
    password,
    clientCredentials,
    authorizationCode,
    extensions
  )

def oAuthFlowGen:Gen[OAuthFlow] = for{
    authorizationUrl<-Gen.option(Gen.alphaNumStr)
    tokenUrl<-Gen.option(Gen.alphaNumStr)
    refreshUrl<-Gen.option(Gen.alphaNumStr)
    scopes<-listMapGen(Gen.alphaNumStr,Gen.alphaNumStr)
    extensions<- listMapGen(Gen.alphaNumStr, extensionValueGen)
} yield OAuthFlow(
    authorizationUrl,
    tokenUrl,
    refreshUrl,
    scopes,
    extensions
  )

def openAPIGen:Gen[OpenAPI] = for {
    openapi<-Gen.alphaNumStr
    info<- infoGen
    jsonSchemaDialect<-Gen.option(Gen.alphaNumStr)
    tags<- Gen.listOf(tagGen)
    servers<-Gen.listOf(serverGen)
    paths <- pathsGen
    webhooks<-Gen.option(mapGen(Gen.alphaNumStr, referenceOrGen(pathItemGen)))
    components<-Gen.option(componentsGen)
    security<- Gen.listOf(securityRequirementGen)
    extensions<-listMapGen(Gen.alphaNumStr, extensionValueGen)
} yield OpenAPI(
    openapi,
    info,
    jsonSchemaDialect,
    tags,
    servers,
    paths ,
    webhooks,
    components,
    security,
    extensions
  )
}
