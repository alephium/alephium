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

package org.alephium.protocol.vm

import org.alephium.io.{MerklePatriciaTrie, StorageFixture}
import org.alephium.protocol.{Hash, Signature}
import org.alephium.protocol.model.minimalGas

trait VMFactory extends StorageFixture {
  lazy val cachedWorldState: WorldState = {
    val db = newDB[Hash, MerklePatriciaTrie.Node]
    WorldState.emptyCached(db)
  }

  lazy val persistedWorldState: WorldState = {
    val db = newDB[Hash, MerklePatriciaTrie.Node]
    WorldState.emptyPersisted(db)
  }

  lazy val statelessContext: StatelessContext =
    StatelessContext(Hash.zero, minimalGas, Stack.ofCapacity[Signature](0))
}
