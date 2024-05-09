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

import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.vm.{GasBox, GasPrice}
import org.alephium.util.{TimeStamp, U256}
import org.alephium.util.Bytes.byteStringOrdering

package object model {
  val DefaultBlockVersion: Byte = 0.toByte
  val DefaultTxVersion: Byte    = 0.toByte

  val cliqueIdLength: Int = PublicKey.length

  // scalastyle:off magic.number
  val minimalGas: GasBox          = GasBox.unsafe(20000)
  val coinbaseGasPrice: GasPrice  = GasPrice(ALPH.nanoAlph(1))
  val coinbaseGasFeeSubsidy: U256 = coinbaseGasPrice * minimalGas

  val defaultGasPerInput: GasBox  = GasBox.unsafe(2500)
  val defaultGasPerOutput: GasBox = GasBox.unsafe(6000)

  val nonCoinbaseMinGasPrice: GasPrice = GasPrice(ALPH.nanoAlph(100))
  val nonCoinbaseMinGasFee: U256       = nonCoinbaseMinGasPrice * minimalGas

  val maximalTxsInOneBlock: Int          = 2000
  val maximalGasPerBlockPreRhone: GasBox = GasBox.unsafe(minimalGas.value * maximalTxsInOneBlock)
  val maximalGasPerBlock: GasBox         = GasBox.unsafe(maximalGasPerBlockPreRhone.value / 4)
  val maximalGasPerTxPreRhone: GasBox    = GasBox.unsafe(maximalGasPerBlockPreRhone.value / 64)
  val maximalGasPerTx: GasBox            = GasBox.unsafe(maximalGasPerBlock.value / 2)

  @inline def getMaximalGasPerBlock(hardFork: HardFork): GasBox = {
    if (hardFork.isRhoneEnabled()) maximalGasPerBlock else maximalGasPerBlockPreRhone
  }

  @inline def getMaximalGasPerTx(hardFork: HardFork): GasBox = {
    if (hardFork.isRhoneEnabled()) maximalGasPerTx else maximalGasPerTxPreRhone
  }

  @inline def getMaximalGasPerTx()(implicit networkConfig: NetworkConfig): GasBox = {
    getMaximalGasPerTx(networkConfig.getHardFork(TimeStamp.now()))
  }

  val maximalCodeSizePreLeman: Int = 12 * 1024 // 12KB
  val maximalCodeSizeLeman: Int    = 4 * 1024  // 4KB
  val maximalCodeSizeRhone: Int    = 32 * 1024 // 32KB
  val maximalFieldSize: Int        = 3 * 1024  // 3KB

  val dustUtxoAmount: U256           = ALPH.nanoAlph(1000000)
  val deprecatedDustUtxoAmount: U256 = ALPH.nanoAlph(1000)
  val maxTokenPerContractUtxo: Int   = 8
  val maxTokenPerAssetUtxo: Int      = 1
  val deprecatedMaxTokenPerUtxo: Int = 64

  val minimalAlphInContractPreRhone: U256 = ALPH.oneAlph
  val minimalAlphInContract: U256         = ALPH.oneAlph.divUnsafe(U256.unsafe(10))

  def minimalContractStorageDeposit(hardFork: HardFork): U256 = {
    if (hardFork.isRhoneEnabled()) minimalAlphInContract else minimalAlphInContractPreRhone
  }

  implicit val hashOrdering: Ordering[Hash] = Ordering.by(_.bytes)
  // scalastyle:on magic.number
}
