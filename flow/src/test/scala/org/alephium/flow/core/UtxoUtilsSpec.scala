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

  it should "return the utxos with the amount from small to large, without tokens" in new Fixture {
    implicit val utxos = buildUtxos(20, 10, 30)

    UtxoSelection(10).verify(1)
    UtxoSelection(20).verify(1, 0)
    UtxoSelection(30).verify(1, 0)
    UtxoSelection(40).verify(1, 0, 2)
    UtxoSelection(50).verify(1, 0, 2)
    UtxoSelection(60).verify(1, 0, 2)
    UtxoSelection(70).leftValue.startsWith(s"Not enough balance") is true

    UtxoSelection(29).withDust(1).verify(1, 0)
    UtxoSelection(29).withDust(2).verify(1, 0, 2)
    UtxoSelection(30).withDust(1).verify(1, 0)
    UtxoSelection(59).withDust(2).leftValue.startsWith(s"Not enough balance") is true
  }

  it should "return the utxos with the amount from small to large, with tokens" in new Fixture {
    val tokenId1 = Hash.hash("tokenId1")
    val tokenId2 = Hash.hash("tokenId2")
    val tokenId3 = Hash.hash("tokenId3")

    implicit val utxos = buildUtxosWithTokens(
      (20, AVector((tokenId1, 10), (tokenId2, 20))),
      (10, AVector.empty),
      (30, AVector((tokenId1, 2), (tokenId3, 10)))
    )

    UtxoSelection(10).verify(1)
    UtxoSelection(20).verify(1, 0)
    UtxoSelection(20, (tokenId1, 1)).verify(1, 0)
    UtxoSelection(20, (tokenId3, 5)).verify(1, 0, 2)
    UtxoSelection(20, (tokenId1, 10)).verify(1, 0)
    UtxoSelection(20, (tokenId1, 11)).verify(1, 0, 2)
    UtxoSelection(20, (tokenId1, 13)).leftValue.startsWith(s"Not enough balance") is true

    UtxoSelection(30).verify(1, 0)
    UtxoSelection(30, (tokenId1, 10), (tokenId2, 15)).verify(1, 0)
    UtxoSelection(40).verify(1, 0, 2)
    UtxoSelection(40, (tokenId1, 10), (tokenId2, 20), (tokenId3, 10)).verify(1, 0, 2)
    UtxoSelection(40, (tokenId1, 10), (tokenId2, 20), (tokenId3, 11)).leftValue
      .startsWith(s"Not enough balance") is true

    UtxoSelection(70, (tokenId1, 1)).leftValue.startsWith(s"Not enough balance") is true
    UtxoSelection(29, (tokenId1, 10), (tokenId2, 15)).withDust(1).verify(1, 0)
    UtxoSelection(29, (tokenId1, 10), (tokenId2, 1)).withDust(2).verify(1, 0, 2)
    UtxoSelection(29, (tokenId1, 10), (tokenId2, 20)).withDust(1).verify(1, 0)
    UtxoSelection(59, (tokenId1, 10))
      .withDust(2)
      .leftValue
      .startsWith(s"Not enough balance") is true
  }

  it should "return the correct utxos when gas is considered" in new Fixture {
    import UtxoUtils._
    val utxos = buildUtxos(20, 10, 30)
    select(utxos, 7) isE Selected(AVector(utxos(1)), 3)
    select(utxos, 8) isE Selected(AVector(utxos(1), utxos(0)), 4)
    select(utxos, 26) isE Selected(AVector(utxos(1), utxos(0)), 4)
    select(utxos, 27) isE Selected(AVector(utxos(1), utxos(0), utxos(2)), 5)
    select(utxos, 55) isE Selected(AVector(utxos(1), utxos(0), utxos(2)), 5)
    select(utxos, 56).leftValue is s"Not enough balance for fee, maybe transfer a smaller amount"

    select(utxos, 25, dustAmount = 2) isE Selected(AVector(utxos(1), utxos(0), utxos(2)), 5)
    select(utxos, 26, dustAmount = 2) isE Selected(AVector(utxos(1), utxos(0)), 4)
  }

  it should "prefer persisted utxos" in new Fixture {
    val utxos0 = buildUtxos(20, 10)
    val utxos1 = AVector(utxos0(0), utxos0(1).copy(outputType = UnpersistedBlockOutput))
    select(utxos1, 7) isE Selected(AVector(utxos1(0)), 3)
  }

  it should "return the correct utxos when gas is preset" in new Fixture {
    val utxos = buildUtxos(20, 10, 30)
    select(utxos, 9, Some(GasBox.unsafe(1))) isE Selected(AVector(utxos(1)), 1)
    select(utxos, 10, Some(GasBox.unsafe(1))) isE Selected(AVector(utxos(1), utxos(0)), 1)
  }

  it should "consider minimal gas" in new Fixture {
    val utxos = buildUtxos(20, 10, 30)
    select(utxos, 1) isE Selected(AVector(utxos(1)), 3)
    select(utxos, 1, minimalGas = 40) isE Selected(AVector(utxos(1), utxos(0), utxos(2)), 40)
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

    val defaultLockupScript = p2pkhLockupGen(GroupIndex.unsafe(0)).sample.get

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
      import UtxoUtils._
      val utxosSorted = utxos.sorted(assetOrderByAlf)
      lazy val result =
        findUtxosWithoutGas(utxosSorted, amount, AVector.from(tokens), dustAmount).map {
          case (_, selectedUtxos, _) => selectedUtxos
        }
      var dustAmount: U256 = U256.Zero

      def verify(utxoIndexes: Int*): Assertion = {
        val selectedUtxos = AVector.from(utxoIndexes).map(utxos(_))
        result isE selectedUtxos
      }

      def withDust(newDustAmount: U256): UtxoSelection = {
        dustAmount = newDustAmount
        this
      }

      def leftValue = result.leftValue
    }

    def selectWithoutGas(
        utxos: AVector[AssetOutputInfo],
        amount: U256,
        tokens: AVector[(TokenId, U256)] = AVector.empty,
        dustAmount: U256 = U256.Zero
    ): Either[String, AVector[AssetOutputInfo]] = {
      import UtxoUtils._
      val utxosSorted = utxos.sorted(assetOrderByAlf)
      findUtxosWithoutGas(utxosSorted, amount, tokens, dustAmount).map {
        case (_, selectedUtxos, _) => selectedUtxos
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
        AVector.empty, // FIXME
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
