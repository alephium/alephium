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

package org.alephium.app

import org.scalatest.EitherValues

import org.alephium.api.model.{BlockEntry, GhostUncleBlockEntry}
import org.alephium.crypto.Blake3
import org.alephium.json.Json._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

class WebSocketCodecSpec extends AlephiumFutureSpec with NoIndexModelGenerators with EitherValues {
  import ServerFixture._

  behavior of "http"

  it should "encode BlockEntry" in new ServerFixture {
    val dep  = BlockHash.unsafe(Blake3.hash("foo"))
    val deps = AVector.fill(groupConfig.depsNum)(dep)
    val blockEntry = BlockEntry(
      BlockHash.random,
      TimeStamp.zero,
      0,
      0,
      1,
      deps,
      AVector.empty,
      Nonce.zero.value,
      0,
      Hash.zero,
      Hash.hash("bar"),
      Target.Max.bits,
      AVector(
        GhostUncleBlockEntry(
          BlockHash.random,
          Address.Asset(LockupScript.p2pkh(PublicKey.generate))
        )
      )
    )
    val result = writeJs(blockEntry)
    show(result) is write(blockEntry)
  }
}
