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
import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.mining.Emission
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.serialize
import org.alephium.util.{AVector, Bytes, TimeStamp, U256}

object Coinbase {
  def powMiningReward(gasFee: U256, reward: Emission.PoW, blockTs: TimeStamp)(implicit
      networkConfig: NetworkConfig
  ): U256 = {
    val hardFork = networkConfig.getHardFork(blockTs)
    Transaction.totalReward(gasFee, reward.miningReward, hardFork)
  }

  @inline
  def calcMainChainReward(miningReward: U256): U256 = {
    val numerator   = U256.unsafe(100 * 8 * 32)
    val denominator = U256.unsafe(5 * 7 * 33 + 100 * 8 * 32)
    miningReward.mulUnsafe(numerator).divUnsafe(denominator)
  }

  @inline
  def calcGhostUncleReward(mainChainReward: U256, heightDiff: Int): U256 = {
    val heightDiffMax = ALPH.MaxGhostUncleAge + 1
    assume(heightDiff > 0 && heightDiff < heightDiffMax)
    val numerator = U256.unsafe(heightDiffMax - heightDiff)
    mainChainReward.mulUnsafe(numerator).divUnsafe(U256.unsafe(heightDiffMax))
  }

  @inline
  def calcBlockReward(mainChainReward: U256, uncleRewards: AVector[U256]): U256 = {
    val inclusionReward = uncleRewards.fold(U256.Zero)(_ addUnsafe _).divUnsafe(U256.unsafe(32))
    mainChainReward.addUnsafe(inclusionReward)
  }

  def coinbaseOutputsPreRhone(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp
  )(implicit networkConfig: NetworkConfig): AVector[AssetOutput] = {
    AVector(
      AssetOutput(miningReward, lockupScript, lockTime, AVector.empty, serialize(coinbaseData))
    )
  }

  def coinbaseOutputsRhone(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp,
      uncles: AVector[SelectedGhostUncle]
  )(implicit networkConfig: NetworkConfig): AVector[AssetOutput] = {
    val mainChainReward    = calcMainChainReward(miningReward)
    val uncleRewardOutputs = uncles.map(_.toAssetOutput(mainChainReward, lockTime))
    val blockRewardOutput = AssetOutput(
      calcBlockReward(mainChainReward, uncleRewardOutputs.map(_.amount)),
      lockupScript,
      lockTime,
      AVector.empty,
      serialize(coinbaseData)
    )
    blockRewardOutput +: uncleRewardOutputs
  }

  def buildPoWCoinbase(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      blockTs: TimeStamp,
      uncles: AVector[SelectedGhostUncle]
  )(implicit networkConfig: NetworkConfig): Transaction = {
    val lockTime = blockTs + networkConfig.coinbaseLockupPeriod
    val hardFork = networkConfig.getHardFork(blockTs)
    val outputs = if (hardFork.isRhoneEnabled()) {
      assume(coinbaseData.isGhostEnabled)
      coinbaseOutputsRhone(coinbaseData, miningReward, lockupScript, lockTime, uncles)
    } else {
      assume(!coinbaseData.isGhostEnabled)
      coinbaseOutputsPreRhone(coinbaseData, miningReward, lockupScript, lockTime)
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

  def buildPoWCoinbase(
      chainIndex: ChainIndex,
      rewardAmount: U256,
      lockupScript: LockupScript.Asset,
      minerData: ByteString,
      blockTs: TimeStamp,
      uncles: AVector[SelectedGhostUncle]
  )(implicit networkConfig: NetworkConfig): Transaction = {
    val sortedUncles = uncles.sortBy(_.blockHash.bytes)(Bytes.byteStringOrdering)
    val coinbaseData = CoinbaseData.from(chainIndex, blockTs, sortedUncles, minerData)
    buildPoWCoinbase(coinbaseData, rewardAmount, lockupScript, blockTs, sortedUncles)
  }

  def calcPoLWCoinbaseRewardOutputs(
      chainIndex: ChainIndex,
      minerLockupScript: LockupScript.Asset,
      uncles: AVector[SelectedGhostUncle],
      reward: Emission.PoLW,
      gasFee: U256,
      blockTs: TimeStamp,
      minerData: ByteString
  )(implicit networkConfig: NetworkConfig): AVector[AssetOutput] = {
    val hardFork           = networkConfig.getHardFork(blockTs)
    val lockedReward       = Transaction.totalReward(gasFee, reward.miningReward, hardFork)
    val netReward          = lockedReward.subUnsafe(reward.burntAmount)
    val mainChainReward    = Coinbase.calcMainChainReward(netReward)
    val lockTime           = blockTs + networkConfig.coinbaseLockupPeriod
    val sortedUncles       = uncles.sortBy(_.blockHash.bytes)(Bytes.byteStringOrdering)
    val uncleRewardOutputs = sortedUncles.map(_.toAssetOutput(mainChainReward, lockTime))
    val blockReward = Coinbase.calcBlockReward(mainChainReward, uncleRewardOutputs.map(_.amount))
    val blockRewardLocked = blockReward.addUnsafe(reward.burntAmount)
    val coinbaseData      = CoinbaseData.from(chainIndex, blockTs, sortedUncles, minerData)
    AssetOutput(
      blockRewardLocked,
      minerLockupScript,
      lockTime,
      AVector.empty,
      serialize(coinbaseData)
    ) +: uncleRewardOutputs
  }
}
