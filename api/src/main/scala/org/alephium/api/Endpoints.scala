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

import com.typesafe.scalalogging.StrictLogging
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.EndpointIO.Example
import sttp.tapir.EndpointOutput.OneOfVariant
import sttp.tapir.oneOfVariantValueMatcher

import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemasLike
import org.alephium.api.endpoints._
import org.alephium.api.model.{Address => _, _}
import org.alephium.json.Json.ReadWriter
import org.alephium.protocol.{ALPH, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.util.TimeStamp

//scalastyle:off file.size.limit

trait Endpoints
    extends InfosEndpoints
    with BlockflowEndpoints
    with AddressesEndpoints
    with TransactionsEndpoints
    with MempoolEndpoints
    with MultisigEndpoints
    with MinersEndpoints
    with ContractsEndpoints
    with UtilsEndpoints
    with EventsEndpoints

trait BaseEndpoints
    extends ApiModelCodec
    with BaseEndpoint
    with EndpointsExamples
    with TapirCodecs
    with TapirSchemasLike
    with StrictLogging {

  implicit def groupConfig: GroupConfig

  val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[Option[TimeStamp]]("toTs"))
      .map { case (from, to) => TimeInterval(from, to) }(timeInterval =>
        (timeInterval.from, timeInterval.toOpt)
      )
      .validate(TimeInterval.validator)

  val counterQuery: EndpointInput[CounterRange] =
    query[Int]("start")
      .and(query[Option[Int]]("limit"))
      .map { case (start, limitOpt) => CounterRange(start, limitOpt) }(counterQuery =>
        (counterQuery.start, counterQuery.limitOpt)
      )
      .validate(CounterRange.validator)

  lazy val chainIndexQuery: EndpointInput[ChainIndex] =
    query[GroupIndex]("fromGroup")
      .and(query[GroupIndex]("toGroup"))
      .map { case (from, to) => ChainIndex(from, to) }(chainIndex =>
        (chainIndex.from, chainIndex.to)
      )

  val outputRefQuery: EndpointInput[OutputRef] =
    query[Int]("hint")
      .and(query[Hash]("key"))
      .map { case (hint, key) => OutputRef(hint, key) }(outputRef =>
        (outputRef.hint, outputRef.key)
      )
}

object Endpoints {
  // scalastyle:off regex
  def error[S <: StatusCode, T <: ApiError[S]: ReadWriter: Schema](
      apiError: ApiError.Companion[S, T],
      matcher: PartialFunction[Any, Boolean]
  )(implicit
      examples: List[Example[T]]
  ): OneOfVariant[T] = {
    oneOfVariantValueMatcher(apiError.statusCode, jsonBody[T].description(apiError.description))(
      matcher
    )
  }
  // scalastyle:on regex

  def jsonBody[T: ReadWriter: Schema](implicit
      examples: List[Example[T]]
  ): EndpointIO.Body[String, T] = {
    alphJsonBody[T].examples(examples)
  }

  def jsonBodyWithAlph[T: ReadWriter: Schema](implicit
      examples: List[Example[T]]
  ): EndpointIO.Body[String, T] = {
    alphJsonBody[T]
      .examples(examples)
      .description(
        s"Format 1: `${ALPH.oneAlph}`\n\n" +
          s"Format 2: `x.y ALPH`, where `1 ALPH = ${ALPH.oneAlph}\n\n" +
          s"Field fromPublicKeyType can be  `default` or `bip340-schnorr`"
      )
  }

  def jsonBodyEither[A: ReadWriter: Schema, B: ReadWriter: Schema](implicit
      aExamples: List[Example[A]],
      bExamples: List[Example[B]]
  ): EndpointOutput.OneOf[Either[A, B], Either[A, B]] = {
    val leftBody =
      jsonBody[A].map(Left(_))(_.swap.getOrElse(throw new RuntimeException("Expect Left value")))
    val rightBody = jsonBody[B].map(Right(_))(_.value)

    oneOf[Either[A, B]](
      oneOfVariantValueMatcher(StatusCode.Ok, leftBody) { case Left(_) => true },
      oneOfVariantValueMatcher(StatusCode.Ok, rightBody) { case Right(_) => true }
    )
  }
}
