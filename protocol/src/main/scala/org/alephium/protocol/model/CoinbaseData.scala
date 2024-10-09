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

import org.alephium.protocol.config.NetworkConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.{_deserialize => _decode, serialize => encode, _}
import org.alephium.util.{AVector, TimeStamp}

final case class CoinbaseDataPrefix private (fromGroup: Byte, toGroup: Byte, blockTs: TimeStamp)

object CoinbaseDataPrefix {
  implicit val prefixSerde: Serde[CoinbaseDataPrefix] =
    Serde.forProduct3(
      CoinbaseDataPrefix.apply,
      prefix => (prefix.fromGroup, prefix.toGroup, prefix.blockTs)
    )

  def from(chainIndex: ChainIndex, blockTs: TimeStamp): CoinbaseDataPrefix = {
    CoinbaseDataPrefix(chainIndex.from.value.toByte, chainIndex.to.value.toByte, blockTs)
  }
}

sealed trait CoinbaseData {
  def prefix: CoinbaseDataPrefix
  def isGhostEnabled: Boolean = this match {
    case _: CoinbaseDataV1 => false
    case _                 => true
  }
}

final case class CoinbaseDataV1(
    prefix: CoinbaseDataPrefix,
    minerData: ByteString
) extends CoinbaseData

final case class GhostUncleData(blockHash: BlockHash, lockupScript: LockupScript.Asset)

object GhostUncleData {
  implicit val ghostUncleDataSerde: Serde[GhostUncleData] =
    Serde.forProduct2(GhostUncleData.apply, d => (d.blockHash, d.lockupScript))

  def from(uncle: SelectedGhostUncle): GhostUncleData =
    GhostUncleData(uncle.blockHash, uncle.lockupScript)
}

final case class CoinbaseDataV2(
    prefix: CoinbaseDataPrefix,
    ghostUncleData: AVector[GhostUncleData],
    minerData: ByteString
) extends CoinbaseData

object CoinbaseData {
  implicit def serde(implicit networkConfig: NetworkConfig): Serde[CoinbaseData] =
    new Serde[CoinbaseData] {
      def _deserialize(input: ByteString): SerdeResult[Staging[CoinbaseData]] = {
        for {
          prefixResult <- _decode[CoinbaseDataPrefix](input)
          hardFork = networkConfig.getHardFork(prefixResult.value.blockTs)
          coinbaseData <-
            if (hardFork.isRhoneEnabled()) {
              for {
                ghostUncleDataResult <- _decode[AVector[GhostUncleData]](prefixResult.rest)
              } yield Staging[CoinbaseData](
                CoinbaseDataV2(
                  prefixResult.value,
                  ghostUncleDataResult.value,
                  ghostUncleDataResult.rest
                ),
                ByteString.empty
              )
            } else {
              Right(
                Staging[CoinbaseData](
                  CoinbaseDataV1(prefixResult.value, prefixResult.rest),
                  ByteString.empty
                )
              )
            }
        } yield coinbaseData
      }

      def serialize(d: CoinbaseData): ByteString = {
        d match {
          case data: CoinbaseDataV1 => encode(data.prefix) ++ data.minerData
          case data: CoinbaseDataV2 =>
            encode(data.prefix) ++ encode(data.ghostUncleData) ++ data.minerData
        }
      }
    }

  def from(
      chainIndex: ChainIndex,
      blockTs: TimeStamp,
      sortedGhostUncles: AVector[SelectedGhostUncle],
      minerData: ByteString
  )(implicit
      networkConfig: NetworkConfig
  ): CoinbaseData = {
    val prefix   = CoinbaseDataPrefix.from(chainIndex, blockTs)
    val hardFork = networkConfig.getHardFork(blockTs)
    if (hardFork.isRhoneEnabled()) {
      CoinbaseDataV2(prefix, sortedGhostUncles.map(GhostUncleData.from), minerData)
    } else {
      CoinbaseDataV1(prefix, minerData)
    }
  }
}
