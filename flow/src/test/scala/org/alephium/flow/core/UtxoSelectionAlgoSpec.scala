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

import scala.util.Random

import akka.util.ByteString
import org.scalatest.compatible.Assertion

import org.alephium.flow.core.FlowUtils.{AssetOutputInfo, PersistedOutput, UnpersistedBlockOutput}
import org.alephium.flow.core.UtxoSelectionAlgo._
import org.alephium.flow.gasestimation._
import org.alephium.flow.setting.AlephiumConfigFixture
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{GasBox, GasPrice, LockupScript}
import org.alephium.util._

// scalastyle:off number.of.methods
class UtxoSelectionAlgoSpec extends AlephiumSpec with LockupScriptGenerators {

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
    }

    {
      info("with tokens")
      val tokenId1 = TokenId.hash("tokenId1")
      val tokenId2 = TokenId.hash("tokenId2")
      val tokenId3 = TokenId.hash("tokenId3")

      implicit val utxos = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10))),
        (25, AVector((tokenId2, 20))),
        (30, AVector.empty),
        (31, AVector((tokenId3, 10))),
        (10, AVector((tokenId1, 1)))
      )

      UtxoSelection(10).verify(2)
      UtxoSelection(20).verify(2)
      UtxoSelection(20, (tokenId1, 1)).verify(4, 2)
      UtxoSelection(20, (tokenId1, 2)).verify(4, 0)
      UtxoSelection(20, (tokenId1, 11)).verify(4, 0)
      UtxoSelection(20, (tokenId1, 12)).leftValueWithoutGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(30).verify(2)
      UtxoSelection(30, (tokenId1, 10), (tokenId2, 15)).verify(4, 0, 1)
      UtxoSelection(40).verify(2, 4)
      UtxoSelection(40, (tokenId1, 10), (tokenId2, 20), (tokenId3, 10)).verify(4, 0, 1, 3)
      UtxoSelection(40, (tokenId1, 10), (tokenId2, 20), (tokenId3, 11)).leftValueWithoutGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(200, (tokenId1, 1)).leftValueWithoutGas
        .startsWith(s"Not enough balance") is true
    }
  }

  // Gas is calculated using GasEstimation.estimateWithP2PKHInputs
  // 1 input:  20000
  // 2 inputs: 22620
  // 3 inputs: 26680
  // 4 inputs: 30740
  // 5 inputs: 34800
  it should "return the correct utxos when gas is considered" in new Fixture {
    {
      info("without tokens")
      implicit val utxos = buildUtxos(40000, 23000, 55000)

      UtxoSelection(7).verifyWithGas(1)
      UtxoSelection(3000).verifyWithGas(1)
      UtxoSelection(3001).verifyWithGas(1, 0)
      UtxoSelection(30000).verifyWithGas(1, 0)
      UtxoSelection(40380).verifyWithGas(1, 0)
      UtxoSelection(40381).verifyWithGas(1, 0, 2)
      UtxoSelection(91320).verifyWithGas(1, 0, 2)
      UtxoSelection(91321).leftValueWithGas.startsWith("Not enough balance for fee") is true
    }

    {
      info("with tokens")
      val tokenId1 = TokenId.hash("tokenId1")
      val tokenId2 = TokenId.hash("tokenId2")
      val tokenId3 = TokenId.hash("tokenId3")

      implicit val utxos = buildUtxosWithTokens(
        (40000, AVector((tokenId1, 10))),
        (35000, AVector((tokenId2, 20))),
        (23000, AVector.empty),
        (55000, AVector((tokenId3, 10))),
        (55010, AVector((tokenId1, 1)))
      )

      UtxoSelection(10).verifyWithGas(2)
      UtxoSelection(10, (tokenId1, 1)).verifyWithGas(4)
      UtxoSelection(10, (tokenId1, 2)).verifyWithGas(4, 0)
      UtxoSelection(10, (tokenId1, 11)).verifyWithGas(4, 0)

      UtxoSelection(23100).verifyWithGas(2, 1)
      UtxoSelection(23100, (tokenId1, 11)).verifyWithGas(4, 0)
      UtxoSelection(23100, (tokenId1, 12)).leftValueWithGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(23100, (tokenId2, 10)).verifyWithGas(1, 2)
      UtxoSelection(23100, (tokenId2, 15), (tokenId3, 5)).verifyWithGas(1, 3)
      UtxoSelection(23100, (tokenId2, 15), (tokenId1, 11)).verifyWithGas(1, 4, 0)
      UtxoSelection(23100, (tokenId2, 21)).leftValueWithGas
        .startsWith(s"Not enough balance") is true

      UtxoSelection(173210, (tokenId2, 15), (tokenId1, 11)).verifyWithGas(1, 4, 0, 2, 3)
      UtxoSelection(173211, (tokenId2, 15), (tokenId1, 11)).leftValueWithGas
        .startsWith(s"Not enough balance") is true
    }
  }

  it should "prefer persisted utxos" in new Fixture {
    {
      info("without tokens")
      val utxos0 = buildUtxos(40000, 23000)
      val utxos1 = AVector(utxos0(0), utxos0(1).copy(outputType = UnpersistedBlockOutput))

      UtxoSelection(7)(utxos0).verifyWithGas(1)
      UtxoSelection(7)(utxos1).verifyWithGas(0)
    }

    {
      info("with tokens")
      val tokenId1 = TokenId.hash("tokenId1")

      val utxos0 = buildUtxosWithTokens(
        (40000, AVector((tokenId1, 10))),
        (23000, AVector((tokenId1, 11)))
      )
      implicit val utxos1 = AVector(utxos0(0).copy(outputType = UnpersistedBlockOutput), utxos0(1))

      UtxoSelection(7, (tokenId1, 10))(utxos0).verifyWithGas(0)
      UtxoSelection(7, (tokenId1, 10)).verifyWithGas(1)
    }
  }

  it should "prefer non-token utxos for ALPH selection" in new Fixture {
    val tokenId = TokenId.generate
    implicit val utxos = buildUtxosWithTokens(
      (20, AVector((tokenId, 10))),
      (10, AVector.empty),
      (30, AVector.empty)
    )

    UtxoSelection(10).verify(1)
    UtxoSelection(20).verify(1, 2)
    UtxoSelection(40).verify(1, 2)
    UtxoSelection(50).verify(1, 2, 0)
  }

  it should "return the correct utxos when gas is preset" in new Fixture {
    {
      info("without tokens")
      implicit val utxos = buildUtxos(20, 10, 30)

      UtxoSelection(7).withGas(1).verifyWithGas(1)
      UtxoSelection(10).withGas(1).verifyWithGas(1, 0)
    }

    {
      info("with tokens")
      val tokenId1 = TokenId.hash("tokenId1")
      val tokenId2 = TokenId.hash("tokenId2")
      val tokenId3 = TokenId.hash("tokenId3")

      implicit val utxos = buildUtxosWithTokens(
        (20, AVector((tokenId1, 10))),
        (25, AVector((tokenId2, 20))),
        (10, AVector.empty),
        (30, AVector((tokenId3, 10))),
        (31, AVector((tokenId1, 1)))
      )

      UtxoSelection(7).withGas(1).verifyWithGas(2)
      UtxoSelection(10).withGas(1).verifyWithGas(2, 0)
      UtxoSelection(10, (tokenId2, 15), (tokenId1, 11)).withGas(1).verifyWithGas(1, 4, 0)
      UtxoSelection(115, (tokenId2, 15), (tokenId1, 11), (tokenId3, 10))
        .withGas(1)
        .verifyWithGas(1, 4, 0, 3, 2)
      UtxoSelection(116, (tokenId2, 15), (tokenId1, 11), (tokenId3, 10))
        .withGas(1)
        .leftValueWithGas
        .startsWith(s"Not enough balance") is true
      UtxoSelection(10, (tokenId2, 15), (tokenId1, 12))
        .withGas(1)
        .leftValueWithGas
        .startsWith(s"Not enough balance") is true
    }
  }

  it should "sort the utxos in specified order" in new Fixture {
    val tokenId1 = TokenId.hash("tokenId1")
    val tokenId2 = TokenId.hash("tokenId2")
    val tokenId3 = TokenId.hash("tokenId3")
    val tokenId4 = TokenId.hash("tokenId4")

    def checkOrderByAlph(
        input: AVector[Asset],
        utxoIndexes: Int*
    ) = {
      val ascending = AVector.from(utxoIndexes).map(input(_))
      input.sorted(AssetAscendingOrder.byAlph) is ascending
      input.sorted(AssetDescendingOrder.byAlph) is ascending.reverse
    }

    def checkOrderByToken(
        input: AVector[Asset],
        tokenId: TokenId,
        utxoIndexes: Int*
    ) = {
      val ascending = AVector.from(utxoIndexes).map(input(_))
      input.sorted(AssetAscendingOrder.byToken(tokenId)) is ascending
      input.sorted(AssetDescendingOrder.byToken(tokenId)) is ascending.reverse
    }

    val utxos = buildUtxosWithTokens(
      (20, AVector((tokenId1, 10), (tokenId2, 2))),
      (10, AVector.empty),
      (5, AVector.empty),
      (6, AVector((tokenId1, 3), (tokenId2, 10))),
      (4, AVector((tokenId1, 3), (tokenId2, 19))),
      (30, AVector((tokenId1, 2), (tokenId3, 10)))
    )

    checkOrderByAlph(AVector.empty)
    checkOrderByToken(AVector.empty, tokenId1)
    checkOrderByToken(AVector.empty, tokenId2)
    checkOrderByToken(AVector.empty, tokenId3)
    checkOrderByToken(AVector.empty, tokenId4)

    checkOrderByAlph(utxos, 4, 2, 3, 1, 0, 5)
    checkOrderByToken(utxos, tokenId1, 5, 4, 3, 0, 2, 1)
    checkOrderByToken(utxos, tokenId2, 0, 3, 4, 2, 1, 5)
    checkOrderByToken(utxos, tokenId3, 5, 4, 2, 3, 1, 0)
    checkOrderByToken(utxos, tokenId4, 4, 2, 3, 1, 0, 5)
  }

  it should "fall back to the descending order when ascending order doesn't work" in new Fixture {
    implicit val utxos = buildUtxos(40000, 20, 55000)

    // Ascending order
    //   68340 = 40000 + 20 + 55000 - 26680
    //   where 26680 is the estimated gas for 3 outputs
    UtxoSelection(68340).verifyWithGas(1, 0, 2)

    // Descending order
    UtxoSelection(68341).verifyWithGas(2, 0)
  }

  it should "work when tx has more potential outputs than max gas allows" in new Fixture {
    // Potentially 1000 outputs
    val tokenIds     = new Array[TokenId](1000)
    val tokenAmounts = new Array[U256](1000)
    val amounts = (0 until 1000).map { i =>
      tokenIds(i) = TokenId.hash(s"tokenId$i")
      val alphAmount = Random.between(1, 1000000)
      tokenAmounts(i) = U256.unsafe(Random.between(1, 1000000))
      (alphAmount, AVector((tokenIds(i), tokenAmounts(i))))
    }.toSeq

    implicit val utxos = buildUtxosWithTokens(amounts: _*)

    UtxoSelection(0).verifyCanSelect()

    val firstAlphAmount = amounts.head._1
    UtxoSelection(firstAlphAmount).verifyCanSelect()
    UtxoSelection(firstAlphAmount, (tokenIds(100), tokenAmounts(100))).verifyCanSelect()
    UtxoSelection(
      firstAlphAmount,
      (tokenIds(1), tokenAmounts(1)),
      (tokenIds(51), tokenAmounts(51)),
      (tokenIds(151), tokenAmounts(151))
    ).verifyCanSelect()

    val firstTenAlphAmount = amounts.take(10).map(_._1).sum
    UtxoSelection(firstTenAlphAmount).verifyCanSelect()
    UtxoSelection(firstTenAlphAmount, (tokenIds(100), tokenAmounts(100))).verifyCanSelect()
    UtxoSelection(
      firstTenAlphAmount,
      (tokenIds(1), tokenAmounts(1)),
      (tokenIds(51), tokenAmounts(51)),
      (tokenIds(151), tokenAmounts(151))
    ).verifyCanSelect()

    val allAlphAmount = amounts.map(_._1).sum
    UtxoSelection(allAlphAmount).verifyCanNotSelect()
    UtxoSelection(allAlphAmount, (tokenIds(101), tokenAmounts(101))).verifyCanNotSelect()
  }

  trait Fixture extends AlephiumConfigFixture {

    def buildOutput(
        lockupScript: LockupScript.Asset,
        tokens: AVector[(TokenId, U256)],
        amount: U256
    ): Asset = {
      val output =
        AssetOutput(amount, lockupScript, TimeStamp.now(), tokens, ByteString.empty)
      val ref = AssetOutputRef.unsafe(Hint.from(output), TxOutputRef.unsafeKey(Hash.generate))
      AssetOutputInfo(ref, output, PersistedOutput)
    }

    val scriptPair          = p2pkScriptGen(GroupIndex.unsafe(0)).sample.get
    val defaultLockupScript = scriptPair.lockup
    val defaultUnlockScript = scriptPair.unlock

    def buildUtxos(amounts: Int*): AVector[Asset] = {
      AVector.from(amounts.map { amount =>
        buildOutput(defaultLockupScript, AVector.empty, U256.unsafe(amount))
      })
    }

    def buildUtxosWithTokens(
        amounts: (Int, AVector[(TokenId, U256)])*
    ): AVector[Asset] = {
      AVector.from(amounts.map { case (amount, tokens) =>
        buildOutput(defaultLockupScript, tokens, U256.unsafe(amount))
      })
    }

    case class UtxoSelection(alph: U256, tokens: (TokenId, U256)*)(implicit
        utxos: AVector[Asset]
    ) {
      val utxosSorted            = utxos.sorted(AssetAscendingOrder.byAlph)
      var gasOpt: Option[GasBox] = None
      val outputs = {
        val lockupScript1 = p2pkhLockupGen(GroupIndex.unsafe(0)).sample.value
        val lockupScript2 = p2pkhLockupGen(GroupIndex.unsafe(0)).sample.value
        val lockupScript3 = p2pkhLockupGen(GroupIndex.unsafe(0)).sample.value
        AVector(lockupScript1, lockupScript2, lockupScript3)
      }

      lazy val valueWithoutGas = {
        SelectionWithoutGasEstimation(AssetAscendingOrder)
          .select(AssetAmounts(alph, AVector.from(tokens)), utxosSorted)
          .map(_.selected)
      }

      lazy val valueWithGas = {
        UtxoSelectionAlgo
          .Build(ProvidedGas(gasOpt, GasPrice(1)))
          .select(
            AssetAmounts(alph, AVector.from(tokens)),
            defaultUnlockScript,
            utxos,
            outputs.length,
            txScriptOpt = None,
            AssetScriptGasEstimator.Mock,
            TxScriptGasEstimator.Mock
          )
      }

      def verify(utxoIndexes: Int*): Assertion = {
        val selectedUtxos = AVector.from(utxoIndexes).map(utxos(_))
        valueWithoutGas isE selectedUtxos
      }

      def verifyWithGas(utxoIndexes: Int*): Assertion = {
        val selectedUtxos = AVector.from(utxoIndexes).map(utxos(_))
        val gas = gasOpt.getOrElse(
          GasEstimation.estimateWithP2PKHInputs(selectedUtxos.length, outputs.length)
        )
        valueWithGas isE Selected(selectedUtxos, gas)
      }

      def verifyCanSelect(): Assertion = {
        valueWithGas.rightValue.assets.length should be < utxos.length
      }

      def verifyCanNotSelect(): Assertion = {
        intercept[AssertionError](valueWithGas.rightValue)
          .getMessage() is "Not enough balance for fee, maybe transfer a smaller amount"
      }

      def withGas(gas: Int): UtxoSelection = {
        gasOpt = Some(GasBox.unsafe(gas))
        this
      }

      def leftValueWithoutGas = valueWithoutGas.leftValue
      def leftValueWithGas    = valueWithGas.leftValue
    }
  }
}
