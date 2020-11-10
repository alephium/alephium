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

package org.alephium.protocol.model

import org.alephium.serde.Serde
import org.alephium.util.TimeStamp

final case class CoinbaseFixedData private (fromGroup: Byte, toGroup: Byte, blockTs: TimeStamp)

object CoinbaseFixedData {
  implicit val serde: Serde[CoinbaseFixedData] =
    Serde.forProduct3(CoinbaseFixedData.apply, t => (t.fromGroup, t.toGroup, t.blockTs))

  def from(chainIndex: ChainIndex, blockTs: TimeStamp): CoinbaseFixedData = {
    CoinbaseFixedData(chainIndex.from.value.toByte, chainIndex.to.value.toByte, blockTs)
  }
}
