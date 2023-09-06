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

import org.alephium.protocol.ALPH
import org.alephium.protocol.model.{Address, TokenId}
import org.alephium.protocol.vm.NotEnoughApprovedBalance
import org.alephium.util.{AlephiumSpec, Hex, U256}

class ExeFailureSpec extends AlephiumSpec {
  it should "test NotEnoughApprovedBalance" in {
    val lockupScript =
      Address.fromBase58("1G5fKUFGRFEHXcTeCfgKQNppbumd8E9G8vA6p2wf4n2L6").get.lockupScript
    val tokenId = TokenId
      .from(Hex.unsafe("cee16cdfad98e8943fd9a5b45e1765e0a6895608efe3eeeb41a60db55f605b46"))
      .get

    NotEnoughApprovedBalance(lockupScript, tokenId, ALPH.alph(1), U256.Zero).toString is
      "NotEnoughApprovedBalance(address: 1G5fKUFGRFEHXcTeCfgKQNppbumd8E9G8vA6p2wf4n2L6,tokenId: cee16cdfad98e8943fd9a5b45e1765e0a6895608efe3eeeb41a60db55f605b46,expected: 1000000000000000000,got: 0)"
    NotEnoughApprovedBalance(lockupScript, TokenId.alph, ALPH.alph(2), ALPH.oneAlph).toString is
      "NotEnoughApprovedBalance(address: 1G5fKUFGRFEHXcTeCfgKQNppbumd8E9G8vA6p2wf4n2L6,tokenId: ALPH,expected: 2000000000000000000,got: 1000000000000000000)"
  }
}
