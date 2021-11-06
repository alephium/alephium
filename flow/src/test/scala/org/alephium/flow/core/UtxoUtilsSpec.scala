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
import org.scalatest.compatible.Assertion

import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, PersistedOutput, UnpersistedBlockOutput}
import org.alephium.flow.core.UtxoUtils._
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
    {
      info("without tokens")
      implicit val utxos = buildUtxos(20, 10, 30)

      UtxoSelection(10).verify(1)
      UtxoSelection(20).verify(1, 0)
      UtxoSelection(30).verify(1, 0)
      UtxoSelection(40).verify(1, 0, 2)
      UtxoSelection(50).verify(1, 0, 2)
      UtxoSelection(60).verify(1, 0, 2)
      UtxoSelection(70).leftValueWithoutGas.startsWith(s"Not enough balance") is true

      UtxoSelection(29).withDust(1).verify(1, 0)
      UtxoSelection(29).withDust(2).verify(1, 0, 2)
      UtxoSelection(30).withDust(1).verify(1, 0)
      UtxoSelection(59).withDust(2).leftValueWithoutGas.startsWith(s"Not enough balance") is true

    }

    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")
      val tokenId3 = Hash.hash("tokenId3")

      implicit val utxos = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10), (tokenId2, 20))),
        (10, AVector.empty),
        (30, AVector((tokenId1, 2), (tokenId3, 10))),
        (31, AVector((tokenId1, 1)))
      )

      UtxoSelection(10).verify(1)
      UtxoSelection(20).verify(1, 0)
      UtxoSelection(20, (tokenId1, 1)).verify(1, 0)
      UtxoSelection(20, (tokenId3, 5)).verify(1, 0, 2)
      UtxoSelection(20, (tokenId1, 10)).verify(1, 0)
      UtxoSelection(20, (tokenId1, 11)).verify(1, 0, 3)
      UtxoSelection(20, (tokenId1, 14)).leftValueWithoutGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(30).verify(1, 0)
      UtxoSelection(30, (tokenId1, 10), (tokenId2, 15)).verify(1, 0)
      UtxoSelection(40).verify(1, 0, 2)
      UtxoSelection(40, (tokenId1, 10), (tokenId2, 20), (tokenId3, 10)).verify(1, 0, 2)
      UtxoSelection(40, (tokenId1, 10), (tokenId2, 20), (tokenId3, 11)).leftValueWithoutGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(92, (tokenId1, 1)).leftValueWithoutGas.startsWith(s"Not enough balance") is true
      UtxoSelection(29, (tokenId1, 10), (tokenId2, 15)).withDust(1).verify(1, 0)
      UtxoSelection(29, (tokenId1, 10), (tokenId2, 1)).withDust(2).verify(1, 0, 2)
      UtxoSelection(29, (tokenId1, 11), (tokenId2, 20)).withDust(1).verify(1, 0, 3)
      UtxoSelection(29, (tokenId1, 13), (tokenId2, 20)).withDust(1).verify(1, 0, 3, 2)
      UtxoSelection(90, (tokenId1, 10))
        .withDust(2)
        .leftValueWithoutGas
        .startsWith(s"Not enough balance") is true
    }
  }

  it should "return the correct utxos when gas is considered" in new Fixture {
    {
      info("without tokens")
      implicit val utxos = buildUtxos(20, 10, 30)

      UtxoSelection(7).verify(gas = 3, 1)
      UtxoSelection(8).verify(gas = 4, 1, 0)
      UtxoSelection(26).verify(gas = 4, 1, 0)
      UtxoSelection(27).verify(gas = 5, 1, 0, 2)
      UtxoSelection(55).verify(gas = 5, 1, 0, 2)
      UtxoSelection(56).leftValueWithGas.startsWith("Not enough balance for fee") is true

      UtxoSelection(25).withDust(2).verify(gas = 5, 1, 0, 2)
      UtxoSelection(26).withDust(2).verify(gas = 4, 1, 0)
    }

    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")
      val tokenId3 = Hash.hash("tokenId3")

      implicit val utxos = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10), (tokenId2, 20))),
        (10, AVector.empty),
        (30, AVector((tokenId1, 2), (tokenId3, 10))),
        (31, AVector((tokenId1, 1)))
      )

      UtxoSelection(10).verify(gas = 4, 1, 0)
      UtxoSelection(10, (tokenId1, 1)).verify(gas = 4, 1, 3)
      UtxoSelection(10, (tokenId1, 2)).verify(gas = 5, 1, 3, 2)
      UtxoSelection(10, (tokenId1, 5)).verify(gas = 6, 1, 3, 2, 0)

      UtxoSelection(20).verify(gas = 4, 1, 0)
      UtxoSelection(20, (tokenId1, 10)).verify(gas = 4, 1, 0)
      UtxoSelection(20, (tokenId1, 11)).verify(gas = 5, 1, 0, 3)
      UtxoSelection(20, (tokenId1, 12)).verify(gas = 6, 1, 0, 3, 2)
      UtxoSelection(20, (tokenId1, 14)).leftValueWithGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(20, (tokenId2, 10)).verify(gas = 4, 1, 0)
      UtxoSelection(20, (tokenId2, 15), (tokenId3, 5)).verify(gas = 5, 1, 0, 2)
      UtxoSelection(20, (tokenId2, 15), (tokenId1, 11)).verify(gas = 5, 1, 0, 3)
      UtxoSelection(20, (tokenId2, 15), (tokenId1, 13)).verify(gas = 6, 1, 0, 3, 2)
      UtxoSelection(30, (tokenId2, 15), (tokenId1, 13)).verify(gas = 6, 1, 0, 3, 2)
      UtxoSelection(30, (tokenId2, 21)).leftValueWithGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(85, (tokenId2, 15), (tokenId1, 13)).verify(gas = 6, 1, 0, 2, 3)
      UtxoSelection(86, (tokenId2, 15), (tokenId1, 13)).leftValueWithGas
        .startsWith(s"Not enough balance") is true
      UtxoSelection(83, (tokenId2, 15), (tokenId1, 13)).withDust(2).verify(gas = 6, 1, 0, 2, 3)
      UtxoSelection(84, (tokenId2, 15), (tokenId1, 13))
        .withDust(2)
        .leftValueWithGas
        .startsWith(s"Not enough balance") is true
    }
  }

  it should "prefer persisted utxos" in new Fixture {
    {
      info("without tokens")
      val utxos0          = buildUtxos(20, 10)
      implicit val utxos1 = AVector(utxos0(0), utxos0(1).copy(outputType = UnpersistedBlockOutput))

      UtxoSelection(7).verify(gas = 3, 0)
    }

    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")

      val utxos0 = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10))),
        (10, AVector((tokenId2, 20)))
      )
      implicit val utxos1 = AVector(utxos0(0), utxos0(1).copy(outputType = UnpersistedBlockOutput))

      UtxoSelection(7).verify(gas = 3, 0)
      UtxoSelection(7, (tokenId1, 10)).verify(gas = 3, 0)
      UtxoSelection(7, (tokenId2, 10)).verify(gas = 4, 0, 1)
    }
  }

  it should "return the correct utxos when gas is preset" in new Fixture {
    {
      info("without tokens")
      implicit val utxos = buildUtxos(20, 10, 30)

      UtxoSelection(7).withGas(1).verify(gas = 1, 1)
      UtxoSelection(10).withGas(1).verify(gas = 1, 1, 0)
    }

    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")
      val tokenId2 = Hash.hash("tokenId2")
      val tokenId3 = Hash.hash("tokenId3")

      implicit val utxos = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10), (tokenId2, 20))),
        (10, AVector.empty),
        (30, AVector((tokenId1, 2), (tokenId3, 10)))
      )

      UtxoSelection(7).withGas(1).verify(gas = 1, 1)
      UtxoSelection(10).withGas(1).verify(gas = 1, 1, 0)
      UtxoSelection(10, (tokenId2, 15), (tokenId1, 12)).withGas(1).verify(gas = 1, 1, 0, 2)
      UtxoSelection(40, (tokenId2, 15), (tokenId1, 12), (tokenId3, 10))
        .withGas(1)
        .verify(gas = 1, 1, 0, 2)
      UtxoSelection(10, (tokenId2, 15), (tokenId1, 13))
        .withGas(1)
        .leftValueWithGas
        .startsWith(s"Not enough balance") is true
    }
  }

  it should "consider minimal gas" in new Fixture {
    {
      info("without tokens")
      implicit val utxos = buildUtxos(20, 10, 30)

      UtxoSelection(1).verify(gas = 3, 1)
      UtxoSelection(1).withMinimalGas(40).verify(gas = 40, 1, 0, 2)
    }

    {
      info("with tokens")
      val tokenId1 = Hash.hash("tokenId1")

      implicit val utxos = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10))),
        (10, AVector.empty),
        (30, AVector((tokenId1, 2)))
      )

      UtxoSelection(1).verify(gas = 3, 1)
      UtxoSelection(1).withMinimalGas(40).verify(gas = 40, 1, 0, 2)
      UtxoSelection(1, (tokenId1, 11)).withMinimalGas(40).verify(gas = 40, 1, 2, 0)
    }
  }

  trait Fixture extends AlephiumConfigFixture {

    def buildOutput(
        lockupScript: LockupScript.Asset,
        tokens: AVector[(TokenId, U256)],
        amount: U256
    ): AssetOutputInfo = {
      val output =
        AssetOutput(amount, lockupScript, TimeStamp.now(), tokens, ByteString.empty)
      val ref = AssetOutputRef.unsafe(Hint.from(output), Hash.generate)
      AssetOutputInfo(ref, output, PersistedOutput)
    }

    val defaultLockupScript = assetLockupGen(GroupIndex.unsafe(0)).sample.get

    def buildUtxos(amounts: Int*): AVector[AssetOutputInfo] = {
      AVector.from(amounts.map { amount =>
        buildOutput(defaultLockupScript, AVector.empty, U256.unsafe(amount))
      })
    }

    def buildUtxosWithTokens(
        amounts: (Int, AVector[(TokenId, U256)])*
    ): AVector[AssetOutputInfo] = {
      AVector.from(amounts.map { case (amount, tokens) =>
        buildOutput(defaultLockupScript, tokens, U256.unsafe(amount))
      })
    }

    case class UtxoSelection(amount: U256, tokens: (TokenId, U256)*)(implicit
        utxos: AVector[AssetOutputInfo]
    ) {
      val utxosSorted            = utxos.sorted(assetOrderByAlph)
      var dustAmount: U256       = U256.Zero
      var gasOpt: Option[GasBox] = None
      var minimalGas: Int        = 1

      lazy val valueWithoutGas =
        findUtxosWithoutGas(utxosSorted, amount, AVector.from(tokens), dustAmount).map {
          case (_, selectedUtxos, _) => selectedUtxos
        }

      lazy val valueWithGas = UtxoUtils.select(
        utxos,
        amount,
        AVector.from(tokens),
        gasOpt,
        GasPrice(1),
        GasBox.unsafe(1),
        GasBox.unsafe(1),
        dustAmount,
        2,
        GasBox.unsafe(minimalGas)
      )

      def verify(utxoIndexes: Int*): Assertion = {
        val selectedUtxos = AVector.from(utxoIndexes).map(utxos(_))
        valueWithoutGas isE selectedUtxos
      }

      def verify(gas: GasBox, utxoIndexes: Int*): Assertion = {
        val selectedUtxos = AVector.from(utxoIndexes).map(utxos(_))
        valueWithGas isE Selected(selectedUtxos, gas)
      }

      def withDust(newDustAmount: U256): UtxoSelection = {
        dustAmount = newDustAmount
        this
      }

      def withGas(gas: Int): UtxoSelection = {
        gasOpt = Some(GasBox.unsafe(gas))
        this
      }

      def withMinimalGas(gas: Int): UtxoSelection = {
        minimalGas = gas
        this
      }

      def leftValueWithoutGas = valueWithoutGas.leftValue
      def leftValueWithGas    = valueWithGas.leftValue
    }
  }
}
