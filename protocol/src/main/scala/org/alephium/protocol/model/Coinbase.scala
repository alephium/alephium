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

  def uncleReward(miningReward: U256): U256 = {
    // FIXME
    miningReward
  }

  def build(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp
  )(implicit networkConfig: NetworkConfig): Transaction = {
    val txOutput = AssetOutput(
      miningReward,
      lockupScript,
      lockTime,
      tokens = AVector.empty,
      serialize(coinbaseData)
    )
    Transaction(
      UnsignedTransaction.coinbase(AVector.empty, AVector(txOutput)),
      scriptExecutionOk = true,
      contractInputs = AVector.empty,
      generatedOutputs = AVector.empty,
      inputSignatures = AVector.empty,
      scriptSignatures = AVector.empty
    )
  }

  // scalastyle:off parameter.number
  def build(
      coinbaseData: CoinbaseData,
      miningReward: U256,
      lockupScript: LockupScript.Asset,
      lockTime: TimeStamp,
      uncleMiners: AVector[LockupScript.Asset]
  )(implicit networkConfig: NetworkConfig): Transaction = {
    val txOutput =
      AssetOutput(miningReward, lockupScript, lockTime, AVector.empty, serialize(coinbaseData))
    val uncleRewardOutputs = uncleMiners.map(
      AssetOutput(uncleReward(miningReward), _, lockTime, AVector.empty, ByteString.empty)
    )
    Transaction(
      UnsignedTransaction.coinbase(AVector.empty, txOutput +: uncleRewardOutputs),
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
      uncles: AVector[(BlockHash, LockupScript.Asset)]
  )(implicit emissionConfig: EmissionConfig, networkConfig: NetworkConfig): Transaction = {
    val coinbaseData = CoinbaseData.from(chainIndex, blockTs, uncles.map(_._1), minerData)
    val lockTime     = blockTs + networkConfig.coinbaseLockupPeriod
    val reward       = miningReward(gasFee, target, blockTs)
    val hardFork     = networkConfig.getHardFork(blockTs)
    if (hardFork.isGhostEnabled()) {
      build(coinbaseData, reward, lockupScript, lockTime, uncles.map(_._2))
    } else {
      build(coinbaseData, reward, lockupScript, lockTime)
    }
  }
}
