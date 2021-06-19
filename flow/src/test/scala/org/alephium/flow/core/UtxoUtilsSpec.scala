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

package org.alephium.flow.core

import akka.util.ByteString

import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, PersistedOutput, UnpersistedBlockOutput}
import org.alephium.flow.core.UtxoUtils.Selected
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util._

// scalastyle:off number.of.methods
class UtxoUtilsSpec extends AlephiumSpec with LockupScriptGenerators {

  implicit val groupConfig = new GroupConfig {
    override def groups: Int = 2
  }

  it should "return the utxos with the amount from small to large" in new Fixture {
    val utxos = buildUtxos(20, 10, 30)

    selectWithoutGas(utxos, 10) is Right(AVector(utxos(1)))
    selectWithoutGas(utxos, 20) is Right(AVector(utxos(1), utxos(0)))
    selectWithoutGas(utxos, 30) is Right(AVector(utxos(1), utxos(0)))
    selectWithoutGas(utxos, 40) is Right(AVector(utxos(1), utxos(0), utxos(2)))
    selectWithoutGas(utxos, 50) is Right(AVector(utxos(1), utxos(0), utxos(2)))
    selectWithoutGas(utxos, 60) is Right(AVector(utxos(1), utxos(0), utxos(2)))
    selectWithoutGas(utxos, 70).leftValue.startsWith(s"Not enough balance") is true

    selectWithoutGas(utxos, 29, 1) is Right(AVector(utxos(1), utxos(0)))
    selectWithoutGas(utxos, 29, 2) is Right(AVector(utxos(1), utxos(0), utxos(2)))
    selectWithoutGas(utxos, 30, 1) is Right(AVector(utxos(1), utxos(0)))
    selectWithoutGas(utxos, 59, 2).leftValue.startsWith(s"Not enough balance") is true
  }

  it should "return the correct utxos when gas is considered" in new Fixture {
    import UtxoUtils._
    val utxos = buildUtxos(20, 10, 30)
    select(utxos, 7) is Right(Selected(AVector(utxos(1)), 3))
    select(utxos, 8) is Right(Selected(AVector(utxos(1), utxos(0)), 4))
    select(utxos, 26) is Right(Selected(AVector(utxos(1), utxos(0)), 4))
    select(utxos, 27) is Right(Selected(AVector(utxos(1), utxos(0), utxos(2)), 5))
    select(utxos, 55) is Right(Selected(AVector(utxos(1), utxos(0), utxos(2)), 5))
    select(utxos, 56).leftValue is s"Not enough balance for fee, maybe transfer a smaller amount"

    select(utxos, 25, dustAmount = 2) is Right(Selected(AVector(utxos(1), utxos(0), utxos(2)), 5))
    select(utxos, 26, dustAmount = 2) is Right(Selected(AVector(utxos(1), utxos(0)), 4))
  }

  it should "prefer persisted utxos" in new Fixture {
    val utxos0 = buildUtxos(20, 10)
    val utxos1 = AVector(utxos0(0), utxos0(1).copy(outputType = UnpersistedBlockOutput))
    select(utxos1, 7) is Right(Selected(AVector(utxos1(0)), 3))
  }

  it should "return the correct utxos when gas is preset" in new Fixture {
    val utxos = buildUtxos(20, 10, 30)
    select(utxos, 9, Some(GasBox.unsafe(1))) is Right(Selected(AVector(utxos(1)), 1))
    select(utxos, 10, Some(GasBox.unsafe(1))) is Right(Selected(AVector(utxos(1), utxos(0)), 1))
  }

  it should "consider minimal gas" in new Fixture {
    val utxos = buildUtxos(20, 10, 30)
    select(utxos, 1) is Right(Selected(AVector(utxos(1)), 3))
    select(utxos, 1, minimalGas = 40) is Right(Selected(AVector(utxos(1), utxos(0), utxos(2)), 40))
  }

  trait Fixture extends AlephiumConfigFixture {

    def buildOutput(lockupScript: LockupScript, amount: U256): AssetOutputInfo = {
      val output =
        AssetOutput(amount, lockupScript, TimeStamp.now(), AVector.empty, ByteString.empty)
      val ref = AssetOutputRef.unsafe(Hint.from(output), Hash.generate)
      AssetOutputInfo(ref, output, PersistedOutput)
    }

    val defaultLockupScript = p2pkhLockupGen(GroupIndex.unsafe(0)).sample.get

    def buildUtxos(amounts: Int*): AVector[AssetOutputInfo] = {
      AVector.from(amounts.map { amount => buildOutput(defaultLockupScript, U256.unsafe(amount)) })
    }

    def selectWithoutGas(
        utxos: AVector[AssetOutputInfo],
        amount: U256,
        dustAmount: U256 = U256.Zero
    ): Either[String, AVector[AssetOutputInfo]] = {
      import UtxoUtils._
      val utxosSorted = utxos.sorted
      findUtxosWithoutGas(utxosSorted, amount, dustAmount).map { case (_, index) =>
        utxosSorted.take(index + 1)
      }
    }

    def select(
        utxos: AVector[AssetOutputInfo],
        amount: U256,
        gasOpt: Option[GasBox] = None,
        dustAmount: U256 = U256.Zero,
        minimalGas: Int = 1
    ): Either[String, UtxoUtils.Selected] = {
      UtxoUtils.select(
        utxos,
        amount,
        gasOpt,
        GasPrice(1),
        GasBox.unsafe(1),
        GasBox.unsafe(1),
        dustAmount,
        2,
        GasBox.unsafe(minimalGas)
      )
    }
  }
}
