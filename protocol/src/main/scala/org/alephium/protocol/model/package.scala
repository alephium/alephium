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
  val defaultBlockVersion: Byte = 0.toByte
  val defaultTxVersion: Byte    = 0.toByte

  val cliqueIdLength: Int = PublicKey.length

  //scalastyle:off magic.number
  val minimalGas: GasBox        = GasBox.unsafe(20000)
  val minimalGasPrice: GasPrice = GasPrice(ALPH.nanoAlph(1))
  val minimalGasFee: U256       = minimalGasPrice * minimalGas

  val defaultGasPerInput: GasBox  = GasBox.unsafe(2500)
  val defaultGasPerOutput: GasBox = GasBox.unsafe(6000)

  val defaultGas: GasBox           = minimalGas
  val defaultGasPrice: GasPrice    = GasPrice(ALPH.nanoAlph(100))
  val defaultGasFee: U256          = defaultGasPrice * defaultGas
  val defaultGasFeePerInput: U256  = defaultGasPrice * defaultGasPerInput
  val defaultGasFeePerOutput: U256 = defaultGasPrice * defaultGasPerOutput

  val maximalTxsInOneBlock: Int  = 2000
  val maximalGasPerBlock: GasBox = GasBox.unsafe(minimalGas.value * maximalTxsInOneBlock)
  val maximalGasPerTx: GasBox    = GasBox.unsafe(minimalGas.value * maximalTxsInOneBlock / 64)

  val maximalScriptSize: Int = 12 * 1024 // 12KB
  val maximalFieldSize: Int  = 3 * 1024  // 2KB

  val dustUtxoAmount: U256 = ALPH.nanoAlph(1000)
  val maxTokenPerUtxo: Int = 64

  def minimalAlphAmountPerTxOutput(tokenNum: Int): U256 = {
    ALPH.nanoAlph(100 * tokenNum.toLong).addUnsafe(dustUtxoAmount)
  }
  //scalastyle:on magic.number

  type TokenId    = Hash
  type ContractId = Hash

  implicit val tokenIdOrder: Ordering[TokenId] = Ordering.by(_.bytes)
}
