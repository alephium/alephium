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

package org.alephium.api.endpoints

import com.typesafe.scalalogging.StrictLogging
import sttp.tapir._

import org.alephium.api._
import org.alephium.api.TapirCodecs
import org.alephium.api.TapirSchemasLike
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.util.TimeStamp

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
