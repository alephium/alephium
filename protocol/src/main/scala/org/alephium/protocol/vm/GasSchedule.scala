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

import org.alephium.macros.Gas
import org.alephium.protocol.PublicKey

//scalastyle:off magic.number number.of.types

trait GasSchedule

trait GasSimple extends GasSchedule {
  def gas(): GasBox
}

trait GasFormula extends GasSchedule {
  def gas(size: Int): GasBox
}
object GasFormula {
  def wordLength(byteLength: Int): Int = (byteLength + 7) / 8
}

trait UpgradedGasFormula extends GasFormula {
  def gasDeprecated(size: Int): GasBox
}

@Gas
trait GasZero extends GasSimple
object GasZero {
  val gas: GasBox = GasBox.unsafe(0)
}

@Gas
trait GasBase extends GasSimple
object GasBase {
  val gas: GasBox = GasBox.unsafe(2)
}

@Gas
trait GasVeryLow extends GasSimple
object GasVeryLow {
  val gas: GasBox = GasBox.unsafe(3)
}

@Gas
trait GasLow extends GasSimple
object GasLow {
  val gas: GasBox = GasBox.unsafe(5)
}

@Gas
trait GasMid extends GasSimple
object GasMid {
  val gas: GasBox = GasBox.unsafe(8)
}

@Gas
trait GasHigh extends GasSimple
object GasHigh {
  val gas: GasBox = GasBox.unsafe(10)
}

trait GasToByte extends GasFormula {
  def gas(byteLength: Int): GasBox = GasToByte.gas(byteLength)
}
object GasToByte {
  val gasPerByte: Int = 1

  def gas(byteLength: Int): GasBox =
    GasBox.unsafe(gasPerByte * GasFormula.wordLength(byteLength))
}

trait GasHash extends GasFormula {
  def gas(byteLength: Int): GasBox = GasHash.gas(byteLength)
}
object GasHash {
  val baseGas: Int         = 30
  val extraGasPerWord: Int = 6

  def gas(byteLength: Int): GasBox =
    GasBox.unsafe(GasHash.baseGas + GasHash.extraGasPerWord * GasFormula.wordLength(byteLength))
}

trait GasBytesEq extends UpgradedGasFormula {
  def gas(byteLength: Int): GasBox = GasBytesEq.gas(byteLength)

  def gasDeprecated(byteLength: Int): GasBox = GasBytesEq.gasDeprecated(byteLength)
}
object GasBytesEq {
  val gasPerWord: Int = 1
  def gas(byteLength: Int): GasBox =
    GasBox.unsafe(GasVeryLow.gas.value + gasPerWord * GasFormula.wordLength(byteLength))

  def gasDeprecated(byteLength: Int): GasBox =
    GasBox.unsafe(gasPerWord * GasFormula.wordLength(byteLength))
}

trait GasBytesConcat extends UpgradedGasFormula {
  def gas(byteLength: Int): GasBox = GasBytesConcat.gas(byteLength)

  def gasDeprecated(byteLength: Int): GasBox = GasBytesConcat.gasDeprecated(byteLength)
}
object GasBytesConcat {
  val gasPerByte: Int              = 1
  def gas(byteLength: Int): GasBox = GasBox.unsafe(GasVeryLow.gas.value + byteLength * gasPerByte)

  def gasDeprecated(byteLength: Int): GasBox = GasBox.unsafe(byteLength * gasPerByte)
}

trait GasBytesSlice extends GasFormula {
  def gas(byteLength: Int): GasBox = GasBytesSlice.gas(byteLength)
}
object GasBytesSlice {
  val gasPerByte: Int = 1
  def gas(byteLength: Int): GasBox =
    GasVeryLow.gas.addUnsafe(GasBox.unsafe(byteLength * gasPerByte))
}

trait GasEncode extends GasBytesSlice

trait GasZeros extends GasBytesEq

@Gas
trait GasSignature extends GasSimple
object GasSignature {
  val gas: GasBox = GasBox.unsafe(2000)
}

@Gas
trait GasEcRecover extends GasSimple
object GasEcRecover {
  val gas: GasBox = GasBox.unsafe(2500)
}

@Gas
trait GasCreate extends GasSimple
object GasCreate {
  val gas: GasBox = GasBox.unsafe(32000)
}

@Gas
trait GasCopyCreate extends GasSimple
object GasCopyCreate {
  val gas: GasBox = GasBox.unsafe(24000)
}

@Gas
trait GasDestroy extends GasSimple
object GasDestroy {
  val gas: GasBox = GasSchedule.txInputBaseGas
}

@Gas
trait GasMigrate extends GasSimple
object GasMigrate {
  val gas: GasBox = GasCreate.gas
}

trait GasLoadContractFields extends GasFormula {
  def gas(size: Int): GasBox = GasBox.unsafe((size + 1) * GasBase.gas.value)
}

@Gas
trait GasBalance extends GasSimple
object GasBalance {
  val gas: GasBox = GasBox.unsafe(30)
}

trait GasCall extends GasFormula {
  def gas(size: Int): GasBox = ??? // should call the companion object instead
}
object GasCall {
  def scriptBaseGas(byteLength: Int): GasBox =
    GasBox.unsafe(byteLength + GasSchedule.callGas.value)

  def fieldsBaseGas(byteLength: Int): GasBox =
    GasBox.unsafe(byteLength)
}

trait GasLog extends GasFormula {
  def gas(n: Int): GasBox = GasLog.gas(n)
}
object GasLog {
  val gasBase: Int    = 100
  val gasPerData: Int = 20

  def gas(n: Int): GasBox = GasBox.unsafe(gasBase + gasPerData * n)
}

object GasSchedule {
  val callGas: GasBox = GasBox.unsafe(200)

  def contractLoadGas(estimatedContractSize: Int): GasBox = {
    GasBox.unsafe(800 + GasFormula.wordLength(estimatedContractSize))
  }

  val contractStateUpdateBaseGas: GasBox = GasBox.unsafe(5000)

  /*
   * The gas cost of a transaction consists of 4 parts
   * 1. a fixed base gas for each transaction
   * 2. gas for each input including the auto generated contract inputs:
   *    2.1. gas for removing the input from the blockchain state trie
   *    2.2. execution gas for the unlock script of the input
   * 3. gas for each output including the auto generated vm outputs:
   *    3.1. gas for adding the output into the blockchain state trie
   * 4. execution gas for the optional tx script
   */
  val txBaseGas: GasBox       = GasBox.unsafe(1000)
  val txInputBaseGas: GasBox  = GasBox.unsafe(2000)
  val txOutputBaseGas: GasBox = GasBox.unsafe(4500)

  val p2pkUnlockGas: GasBox = {
    GasBox.unsafe(GasHash.gas(PublicKey.length).value + GasSignature.gas.value)
  }

  def p2mpkUnlockGas(m: Int): GasBox = p2pkUnlockGas.mulUnsafe(m)
}
