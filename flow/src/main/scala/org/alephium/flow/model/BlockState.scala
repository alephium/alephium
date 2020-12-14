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

import java.math.BigInteger

import org.alephium.serde.Serde

final case class BlockState(height: Int, weight: BigInteger, chainWeight: BigInteger)

object BlockState {
  implicit val serde: Serde[BlockState] =
    Serde.forProduct3(BlockState(_, _, _), t => (t.height, t.weight, t.chainWeight))
}
