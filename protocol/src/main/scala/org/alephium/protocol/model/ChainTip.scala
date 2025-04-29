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

import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.{intSerde, Serde}

final case class ChainTip(hash: BlockHash, height: Int, weight: Weight) {
  private var _chainIndex: Option[ChainIndex] = None

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    _chainIndex match {
      case Some(index) => index
      case None =>
        val index = ChainIndex.from(hash)
        _chainIndex = Some(index)
        index
    }
  }
}

object ChainTip {
  implicit val serde: Serde[ChainTip] =
    Serde.forProduct3(ChainTip.apply, v => (v.hash, v.height, v.weight))
}
