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

import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{EmissionConfig, NetworkConfig}
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.serialize
import org.alephium.util.{AVector, TimeStamp, U256}

object Coinbase {
  def miningReward(gasFee: U256, target: Target, blockTs: TimeStamp)(implicit
      emissionConfig: EmissionConfig,
      networkConfig: NetworkConfig
  ): U256 = {
    val miningReward = emissionConfig.emission.reward(target, blockTs, ALPH.LaunchTimestamp)
    val hardFork     = networkConfig.getHardFork(blockTs)
    miningReward match {
      case Emission.PoW(miningReward) => Transaction.totalReward(gasFee, miningReward, hardFork)
      case _: Emission.PoLW           => ??? // TODO: when hashrate is high enough
    }
  }

  @inline
  def calcMainChainReward(miningReward: U256): U256 = {
    val numerator   = U256.unsafe(100 * 8 * 32)
    val denominator = U256.unsafe(5 * 7 * 33 + 100 * 8 * 32)
    miningReward.mulUnsafe(numerator).divUnsafe(denominator)
  }

  @inline
  def calcUncleReward(mainChainReward: U256, heightDiff: Int): U256 = {
    assume(heightDiff > 0 && heightDiff < 8)
    val numerator = U256.unsafe(8 - heightDiff)
    mainChainReward.mulUnsafe(numerator).divUnsafe(U256.unsafe(8))
  }

  @inline
  def calcBlockReward(mainChainReward: U256, uncleRewards: AVector[U256]): U256 = {
    val inclusionReward = uncleRewards.fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.unsafe(32))
    mainChainReward.addUnsafe(inclusionReward)
  }

  def coinbaseOutputsPreGhost(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp
  )(implicit networkConfig: NetworkConfig): AVector[AssetOutput] = {
    AVector(
      AssetOutput(miningReward, lockupScript, lockTime, AVector.empty, serialize(coinbaseData))
    )
  }

  def coinbaseOutputsGhost(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp,
      uncles: AVector[SelectedUncle]
  )(implicit networkConfig: NetworkConfig): AVector[AssetOutput] = {
    val mainChainReward = calcMainChainReward(miningReward)
    val uncleRewardOutputs = uncles.map { uncle =>
      val uncleReward = calcUncleReward(mainChainReward, uncle.heightDiff)
      AssetOutput(uncleReward, uncle.lockupScript, lockTime, AVector.empty, ByteString.empty)
    }
    val blockRewardOutput = AssetOutput(
      calcBlockReward(mainChainReward, uncleRewardOutputs.map(_.amount)),
      lockupScript,
      lockTime,
      AVector.empty,
      serialize(coinbaseData)
    )
    blockRewardOutput +: uncleRewardOutputs
  }

  // scalastyle:off parameter.number
  def build(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      blockTs: TimeStamp,
      uncles: AVector[SelectedUncle]
  )(implicit networkConfig: NetworkConfig): Transaction = {
    val lockTime = blockTs + networkConfig.coinbaseLockupPeriod
    val hardFork = networkConfig.getHardFork(blockTs)
    val outputs = if (hardFork.isGhostEnabled()) {
      assume(coinbaseData.isGhostEnabled)
      coinbaseOutputsGhost(coinbaseData, miningReward, lockupScript, lockTime, uncles)
    } else {
      assume(!coinbaseData.isGhostEnabled)
      coinbaseOutputsPreGhost(coinbaseData, miningReward, lockupScript, lockTime)
    }
    Transaction(
      UnsignedTransaction.coinbase(AVector.empty, outputs),
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs = AVector.empty,
      inputSignatures = AVector.empty,
      scriptSignatures = AVector.empty
    )
  }

  // scalastyle:off parameter.number
  def build(
      chainIndex: ChainIndex,
      gasFee: U256,
      lockupScript: LockupScript.Asset,
      minerData: ByteString,
      target: Target,
      blockTs: TimeStamp,
      uncles: AVector[SelectedUncle]
  )(implicit emissionConfig: EmissionConfig, networkConfig: NetworkConfig): Transaction = {
    val coinbaseData = CoinbaseData.from(chainIndex, blockTs, uncles.map(_.blockHash), minerData)
    val reward       = miningReward(gasFee, target, blockTs)
    build(coinbaseData, reward, lockupScript, blockTs, uncles)
  }
}
