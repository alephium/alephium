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

package org.alephium.api.model

import org.alephium.protocol.PublicKey
import org.alephium.protocol.model.{Address, TokenId}
import org.alephium.util.{AlephiumSpec, AVector, U256}

class AddressAssetStateSpec extends AlephiumSpec {
  it should "merge AddressAssetState" in {
    val address1 = Address.p2pkh(PublicKey.generate)
    val address2 = Address.p2pkh(PublicKey.generate)
    val tokenId1 = TokenId.generate
    val tokenId2 = TokenId.generate
    val tokenId3 = TokenId.generate

    val state1 = AddressAssetState(address1, U256.One, Some(AVector(Token(tokenId1, U256.One))))
    val state2 = AddressAssetState(address1, U256.Two, Some(AVector(Token(tokenId1, U256.Ten))))
    val state3 = AddressAssetState(address2, U256.Ten, Some(AVector(Token(tokenId2, U256.Two))))
    val state4 = AddressAssetState(address1, U256.Ten, Some(AVector(Token(tokenId3, U256.Ten))))

    info("Same address with same token")
    AddressAssetState.merge(AVector(state1, state2)) isE AVector(
      AddressAssetState(address1, U256.unsafe(3), Some(AVector(Token(tokenId1, U256.unsafe(11)))))
    )

    info("Same address with different tokens")
    AddressAssetState.merge(AVector(state1, state4)) isE AVector(
      AddressAssetState(
        address1,
        U256.unsafe(11),
        Some(AVector(Token(tokenId1, U256.One), Token(tokenId3, U256.Ten)))
      )
    )

    info("Different addresses")
    AddressAssetState.merge(AVector(state1, state3)) isE AVector(
      AddressAssetState(address1, U256.One, Some(AVector(Token(tokenId1, U256.One)))),
      AddressAssetState(address2, U256.Ten, Some(AVector(Token(tokenId2, U256.Two))))
    )

    AddressAssetState.merge(AVector(state1, state2, state3)) isE AVector(
      AddressAssetState(address1, U256.unsafe(3), Some(AVector(Token(tokenId1, U256.unsafe(11))))),
      AddressAssetState(address2, U256.Ten, Some(AVector(Token(tokenId2, U256.Two))))
    )

    info("One with no tokens")
    val stateNoToken = AddressAssetState(address1, U256.One, None)
    AddressAssetState.merge(AVector(state1, stateNoToken)) isE AVector(
      AddressAssetState(address1, U256.Two, Some(AVector(Token(tokenId1, U256.One))))
    )

    info("Both with no tokens")
    AddressAssetState.merge(AVector(stateNoToken, stateNoToken)) isE AVector(
      AddressAssetState(address1, U256.Two, None)
    )

    info("Alph amount overflow")
    val stateMaxAmount = AddressAssetState(address1, U256.MaxValue, None)
    AddressAssetState.merge(AVector(state1, stateMaxAmount)).isLeft is true

    info("Token amount overflow")
    val stateMaxToken = AddressAssetState(
      address1,
      U256.One,
      Some(AVector(Token(tokenId1, U256.MaxValue)))
    )
    AddressAssetState.merge(AVector(state1, stateMaxToken)).isLeft is true

    info("Add all")
    AddressAssetState.merge(AVector(state1, state2, state3, state4, stateNoToken)) isE AVector(
      AddressAssetState(
        address1,
        U256.unsafe(14),
        Some(AVector(Token(tokenId1, U256.unsafe(11)), Token(tokenId3, U256.Ten)))
      ),
      AddressAssetState(address2, U256.Ten, Some(AVector(Token(tokenId2, U256.Two))))
    )
  }
}
