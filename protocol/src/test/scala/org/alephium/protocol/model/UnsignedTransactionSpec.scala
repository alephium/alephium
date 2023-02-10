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

package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.protocol.{ALPH, PublicKey}
import org.alephium.protocol.model.UnsignedTransaction.TxOutputInfo
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AlephiumSpec, AVector, NumericHelpers, TimeStamp, U256}

class UnsignedTransactionSpec extends AlephiumSpec with NumericHelpers {
  behavior of "Build outputs"

  trait BuildOutputsFixture extends Fixture

  it should "build outputs for ALPH" in new BuildOutputsFixture {
    UnsignedTransaction.buildOutputs(outputInfo(dustUtxoAmount - 1)) is
      AVector(output(dustUtxoAmount))
    UnsignedTransaction.buildOutputs(outputInfo(dustUtxoAmount)) is
      AVector(output(dustUtxoAmount))
    UnsignedTransaction.buildOutputs(outputInfo(ALPH.oneAlph)) is
      AVector(output(ALPH.oneAlph))
  }

  it should "build outputs for token" in new BuildOutputsFixture {
    UnsignedTransaction.buildOutputs(outputInfo(U256.Zero, AVector(tokenId0 -> 1))) is
      AVector(output(dustUtxoAmount, tokenId0, 1))
    UnsignedTransaction.buildOutputs(
      outputInfo(U256.Zero, AVector(tokenId0 -> 1, tokenId1 -> 2))
    ) is AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount, tokenId1, 2))
  }

  it should "build outputs for ALPH and token" in new BuildOutputsFixture {
    UnsignedTransaction.buildOutputs(outputInfo(dustUtxoAmount - 1, AVector(tokenId0 -> 1))) is
      AVector(output(dustUtxoAmount, tokenId0, 1))
    UnsignedTransaction.buildOutputs(outputInfo(dustUtxoAmount, AVector(tokenId0 -> 1))) is
      AVector(output(dustUtxoAmount, tokenId0, 1))
    UnsignedTransaction.buildOutputs(outputInfo(dustUtxoAmount + 1, AVector(tokenId0 -> 1))) is
      AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount))
    UnsignedTransaction.buildOutputs(outputInfo(dustUtxoAmount * 3, AVector(tokenId0 -> 1))) is
      AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount * 2))

    UnsignedTransaction.buildOutputs(
      outputInfo(dustUtxoAmount * 2 - 1, AVector(tokenId0 -> 1, tokenId1 -> 2))
    ) is AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount, tokenId1, 2))
    UnsignedTransaction.buildOutputs(
      outputInfo(dustUtxoAmount * 2, AVector(tokenId0 -> 1, tokenId1 -> 2))
    ) is AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount, tokenId1, 2))
    UnsignedTransaction.buildOutputs(
      outputInfo(dustUtxoAmount * 2 + 1, AVector(tokenId0 -> 1, tokenId1 -> 2))
    ) is AVector(
      output(dustUtxoAmount, tokenId0, 1),
      output(dustUtxoAmount, tokenId1, 2),
      output(dustUtxoAmount)
    )
    UnsignedTransaction.buildOutputs(
      outputInfo(dustUtxoAmount * 4, AVector(tokenId0 -> 1, tokenId1 -> 2))
    ) is AVector(
      output(dustUtxoAmount, tokenId0, 1),
      output(dustUtxoAmount, tokenId1, 2),
      output(dustUtxoAmount * 2)
    )
  }

  behavior of "Change outputs"

  trait ChangeOutputFixture extends Fixture {
    def calculateChangeOutputs(
        alphRemainder: U256,
        tokensRemainder: AVector[(TokenId, U256)]
    ): Either[String, AVector[AssetOutput]] = {
      UnsignedTransaction.calculateChangeOutputs(alphRemainder, tokensRemainder, lockupScript)
    }
  }

  it should "calculate change outputs for ALPH" in new ChangeOutputFixture {
    calculateChangeOutputs(0, AVector.empty) isE AVector.empty[AssetOutput]
    calculateChangeOutputs(dustUtxoAmount - 1, AVector.empty).leftValue is
      "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount, AVector.empty) isE
      AVector(output(dustUtxoAmount))
    calculateChangeOutputs(dustUtxoAmount + 1, AVector.empty) isE
      AVector(output(dustUtxoAmount + 1))
  }

  it should "calculate change outputs for token" in new ChangeOutputFixture {
    calculateChangeOutputs(0, AVector(tokenId0 -> 1)).leftValue is
      "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount - 1, AVector(tokenId0 -> 1)).leftValue is
      "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount, AVector(tokenId0 -> 1)) isE
      AVector(output(dustUtxoAmount, tokenId0, 1))
    calculateChangeOutputs(dustUtxoAmount + 1, AVector(tokenId0 -> 1)).leftValue is
      "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount * 2 - 1, AVector(tokenId0 -> 1)).leftValue is
      "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount * 2, AVector(tokenId0 -> 1)) isE
      AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount))

    calculateChangeOutputs(0, AVector(tokenId0 -> 1, tokenId1 -> 1)).leftValue is
      "Not enough ALPH for change output"
    calculateChangeOutputs(
      dustUtxoAmount * 2 - 1,
      AVector(tokenId0 -> 1, tokenId1 -> 1)
    ).leftValue is "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount * 2, AVector(tokenId0 -> 1, tokenId1 -> 1)) isE
      AVector(output(dustUtxoAmount, tokenId0, 1), output(dustUtxoAmount, tokenId1, 1))
    calculateChangeOutputs(
      dustUtxoAmount * 2 + 1,
      AVector(tokenId0 -> 1, tokenId1 -> 1)
    ).leftValue is "Not enough ALPH for change output"
    calculateChangeOutputs(
      dustUtxoAmount * 3 - 1,
      AVector(tokenId0 -> 1, tokenId1 -> 1)
    ).leftValue is "Not enough ALPH for change output"
    calculateChangeOutputs(dustUtxoAmount * 3, AVector(tokenId0 -> 1, tokenId1 -> 1)) isE
      AVector(
        output(dustUtxoAmount, tokenId0, 1),
        output(dustUtxoAmount, tokenId1, 1),
        output(dustUtxoAmount)
      )
  }

  behavior of "Total amount"

  import UnsignedTransaction.calculateTotalAmountNeeded

  trait TotalAmountFixture extends Fixture {}

  it should "calculate total amount needed for ALPH" in new TotalAmountFixture {
    calculateTotalAmountNeeded(AVector(outputInfo(dustUtxoAmount - 1))) isE
      ((dustUtxoAmount * 2, AVector.empty[(TokenId, U256)], 2))
    calculateTotalAmountNeeded(AVector(outputInfo(dustUtxoAmount))) isE
      ((dustUtxoAmount * 2, AVector.empty[(TokenId, U256)], 2))
    calculateTotalAmountNeeded(AVector(outputInfo(dustUtxoAmount + 1))) isE
      ((dustUtxoAmount * 2 + 1, AVector.empty[(TokenId, U256)], 2))
  }

  it should "calculate total amount needed for token" in new TotalAmountFixture {
    calculateTotalAmountNeeded(AVector(outputInfo(dustUtxoAmount, AVector(tokenId0 -> 1)))) isE
      ((dustUtxoAmount * 3, AVector(tokenId0 -> U256.One), 3))
    calculateTotalAmountNeeded(
      AVector(outputInfo(dustUtxoAmount * 2, AVector(tokenId0 -> 1, tokenId1 -> 2)))
    ) isE
      ((dustUtxoAmount * 5, AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two), 5))
    calculateTotalAmountNeeded(
      AVector(
        outputInfo(dustUtxoAmount, AVector(tokenId0 -> 1)),
        outputInfo(dustUtxoAmount, AVector(tokenId1 -> 2))
      )
    ) isE ((dustUtxoAmount * 5, AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two), 5))
    calculateTotalAmountNeeded(
      AVector(
        outputInfo(dustUtxoAmount * 2, AVector(tokenId0 -> 1, tokenId1 -> 2)),
        outputInfo(dustUtxoAmount * 2, AVector(tokenId0 -> 3, tokenId1 -> 4))
      )
    ) isE ((dustUtxoAmount * 7, AVector(tokenId0 -> U256.unsafe(4), tokenId1 -> U256.unsafe(6)), 7))
  }

  it should "calculate total amount needed for ALPH and token" in new TotalAmountFixture {
    calculateTotalAmountNeeded(AVector(outputInfo(dustUtxoAmount + 1, AVector(tokenId0 -> 1)))) isE
      ((dustUtxoAmount * 4, AVector(tokenId0 -> U256.One), 4))
    calculateTotalAmountNeeded(
      AVector(outputInfo(dustUtxoAmount * 2 + 1, AVector(tokenId0 -> 1, tokenId1 -> 2)))
    ) isE
      ((dustUtxoAmount * 6, AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two), 6))
    calculateTotalAmountNeeded(
      AVector(
        outputInfo(dustUtxoAmount + 1, AVector(tokenId0 -> 1)),
        outputInfo(dustUtxoAmount + 1, AVector(tokenId1 -> 2))
      )
    ) isE ((dustUtxoAmount * 7, AVector(tokenId0 -> U256.One, tokenId1 -> U256.Two), 7))
    calculateTotalAmountNeeded(
      AVector(
        outputInfo(dustUtxoAmount * 2 + 1, AVector(tokenId0 -> 1, tokenId1 -> 2)),
        outputInfo(dustUtxoAmount * 2 + 1, AVector(tokenId0 -> 3, tokenId1 -> 4))
      )
    ) isE ((dustUtxoAmount * 9, AVector(tokenId0 -> U256.unsafe(4), tokenId1 -> U256.unsafe(6)), 9))
  }

  trait Fixture {
    val lockupScript = LockupScript.p2pkh(PublicKey.generate)
    val tokenId0     = TokenId.generate
    val tokenId1     = TokenId.generate

    def tokens(
        tokenId: TokenId = TokenId.zero,
        tokenAmount: U256 = U256.Zero
    ): AVector[(TokenId, U256)] = {
      if (tokenId == TokenId.zero) {
        AVector.empty[(TokenId, U256)]
      } else {
        AVector(tokenId -> tokenAmount)
      }
    }

    def output(
        amount: U256,
        tokenId: TokenId = TokenId.zero,
        tokenAmount: U256 = U256.Zero
    ): AssetOutput = {
      AssetOutput(
        amount,
        lockupScript,
        TimeStamp.zero,
        tokens(tokenId, tokenAmount),
        ByteString.empty
      )
    }

    def outputInfo(
        amount: U256,
        _tokens: AVector[(TokenId, U256)] = AVector.empty
    ): TxOutputInfo = {
      TxOutputInfo(lockupScript, amount, _tokens.flatMap(tokens.tupled), None, None)
    }
  }
}
