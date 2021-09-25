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

package org.alephium.flow.model

import org.alephium.protocol.model.{ChainIndex, GroupIndex}
import org.alephium.serde._
import org.alephium.util.TimeStamp

final case class ReadyTxInfo(chainIndex: ChainIndex, timestamp: TimeStamp)

object ReadyTxInfo {
  private val chainIndexSerde: Serde[ChainIndex] =
    Serde.forProduct2[Int, Int, ChainIndex](
      (from, to) => ChainIndex(new GroupIndex(from), new GroupIndex(to)),
      chainIndex => (chainIndex.from.value, chainIndex.to.value)
    )

  implicit val serde: Serde[ReadyTxInfo] = Serde.forProduct2[ChainIndex, TimeStamp, ReadyTxInfo](
    ReadyTxInfo.apply,
    info => (info.chainIndex, info.timestamp)
  )(chainIndexSerde, serdeImpl[TimeStamp])
}
