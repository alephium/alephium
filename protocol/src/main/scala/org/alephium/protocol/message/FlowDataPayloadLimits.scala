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

package org.alephium.protocol.message

import scala.reflect.ClassTag

import org.apache.pekko.util.ByteString

import org.alephium.protocol.ALPH
import org.alephium.serde._
import org.alephium.util.AVector

object FlowDataPayloadLimits {
  val MaxBlockHeightsPerSyncRequest: Int  = 800
  val MaxHeaderHeightsPerSyncRequest: Int = 2048
  val MinHashesPerRequest: Int            = MaxBlockHeightsPerSyncRequest * 2
  val MaxHashesPerChain: Int              = 100

  // Each header range contains at most 16 heights. Block responses can also contain forks, so
  // reserve three entries per requested main-chain height (the block plus two ghost uncles).
  val MaxHeadersPerHeightRange: Int  = 16
  val MaxHeadersPerSyncResponse: Int = MaxHeaderHeightsPerSyncRequest
  val MaxBlocksPerSyncResponse: Int =
    MaxBlockHeightsPerSyncRequest * (ALPH.MaxGhostUncleSize + 1)

  def maxHashesPerRequest(chainNum: Int): Int = {
    val scaled = MaxHashesPerChain.toLong * chainNum.toLong
    math.min(Int.MaxValue.toLong, math.max(MinHashesPerRequest.toLong, scaled)).toInt
  }

  private[message] def nestedSerde[T: ClassTag](maxOuter: Int, maxInner: Int, maxTotal: Int)(
      implicit elementSerde: Serde[T]
  ): Serde[AVector[AVector[T]]] = new Serde[AVector[AVector[T]]] {
    private val elementVectorSerde = avectorSerde[T]

    override def serialize(input: AVector[AVector[T]]): ByteString = {
      implicit val innerSerde: Serde[AVector[T]] = elementVectorSerde
      avectorSerde[AVector[T]].serialize(input)
    }

    override def _deserialize(input: ByteString): SerdeResult[Staging[AVector[AVector[T]]]] = {
      var total = 0L
      implicit val innerSerde: Serde[AVector[T]] = new Serde[AVector[T]] {
        override def serialize(input: AVector[T]): ByteString = elementVectorSerde.serialize(input)

        override def _deserialize(input: ByteString): SerdeResult[Staging[AVector[T]]] = {
          intSerde._deserialize(input).flatMap { case Staging(size, rest) =>
            val nextTotal = total + size.toLong
            if (size < 0) {
              Left(SerdeError.validation(s"Negative array size: $size"))
            } else if (size > maxInner) {
              Left(SerdeError.validation(s"Too many vector elements: $size, max: $maxInner"))
            } else if (nextTotal > maxTotal.toLong) {
              Left(
                SerdeError.validation(
                  s"Too many nested vector elements: $nextTotal, max: $maxTotal"
                )
              )
            } else {
              fixedSizeSerde[T](size)._deserialize(rest).map { result =>
                total = nextTotal
                result
              }
            }
          }
        }
      }
      avectorSerde[AVector[T]](maxOuter)._deserialize(input)
    }
  }
}
