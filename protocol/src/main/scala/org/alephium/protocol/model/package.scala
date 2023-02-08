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

import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.Bytes.byteStringOrdering
import org.alephium.util.U256

package object model {
  val DefaultBlockVersion: Byte = 0.toByte
  val DefaultTxVersion: Byte    = 0.toByte

  val cliqueIdLength: Int = PublicKey.length

  // scalastyle:off magic.number
  val minimalGas: GasBox         = GasBox.unsafe(20000)
  val coinbaseGasPrice: GasPrice = GasPrice(ALPH.nanoAlph(1))
  val coinbaseGasFee: U256       = coinbaseGasPrice * minimalGas

  val defaultGasPerInput: GasBox  = GasBox.unsafe(2500)
  val defaultGasPerOutput: GasBox = GasBox.unsafe(6000)

  val nonCoinbaseMinGasPrice: GasPrice = GasPrice(ALPH.nanoAlph(100))
  val nonCoinbaseMinGasFee: U256       = nonCoinbaseMinGasPrice * minimalGas

  val maximalTxsInOneBlock: Int  = 2000
  val maximalGasPerBlock: GasBox = GasBox.unsafe(minimalGas.value * maximalTxsInOneBlock)
  val maximalGasPerTx: GasBox    = GasBox.unsafe(minimalGas.value * maximalTxsInOneBlock / 64)

  val maximalCodeSizePreLeman: Int = 12 * 1024 // 12KB
  val maximalCodeSizeLeman: Int    = 4 * 1024  // 4KB
  val maximalFieldSize: Int        = 3 * 1024  // 3KB

  val dustUtxoAmount: U256           = ALPH.nanoAlph(1000000)
  val deprecatedDustUtxoAmount: U256 = ALPH.nanoAlph(1000)
  val maxTokenPerUtxo: Int           = 4
  val deprecatedMaxTokenPerUtxo: Int = 64

  val minimalAlphInContract: U256 = ALPH.oneAlph

  def minimalAttoAlphAmountPerTxOutput(tokenNum: Int): U256 = {
    ALPH.nanoAlph(100 * tokenNum.toLong).addUnsafe(dustUtxoAmount)
  }

  implicit val hashOrdering: Ordering[Hash] = Ordering.by(_.bytes)
  // scalastyle:on magic.number
}
