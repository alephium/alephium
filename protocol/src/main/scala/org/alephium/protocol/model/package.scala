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

package org.alephium.protocol

import org.alephium.protocol.vm.GasBox
import org.alephium.util.Bytes.byteStringOrdering
import org.alephium.util.U256

package object model {
  val cliqueIdLength: Int = PublicKey.length

  //scalastyle:off magic.number
  // TODO: improve gas mechanism
  val minimalGas: GasBox          = GasBox.unsafe(100000)
  val defaultGasPerInput: GasBox  = GasBox.unsafe(10000)
  val defaultGasPerOutput: GasBox = GasBox.unsafe(10000)

  val defaultGasPrice: U256        = ALF.nanoAlf(1)
  val defaultGasFee: U256          = defaultGasPrice.mulUnsafe(minimalGas.toU256)
  val defaultGasFeePerInput: U256  = defaultGasPrice.mulUnsafe(defaultGasPerInput.toU256)
  val defaultGasFeePerOutput: U256 = defaultGasPrice.mulUnsafe(defaultGasPerOutput.toU256)
  //scalastyle:on magic.number

  type TokenId    = Hash
  type ContractId = Hash

  implicit val tokenIdOrder: Ordering[TokenId] = Ordering.by(_.bytes)
}
