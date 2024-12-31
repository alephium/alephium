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
import org.alephium.serde.{_deserialize => _decode, deserialize => decode, serialize => encode, _}
import org.alephium.util.{AVector, TimeStamp}

final case class CoinbaseDataPrefixV1 private (fromGroup: Byte, toGroup: Byte, blockTs: TimeStamp)

object CoinbaseDataPrefixV1 {
  implicit val prefixSerde: Serde[CoinbaseDataPrefixV1] =
    Serde.forProduct3(
      CoinbaseDataPrefixV1.apply,
      prefix => (prefix.fromGroup, prefix.toGroup, prefix.blockTs)
    )

  def from(chainIndex: ChainIndex, blockTs: TimeStamp): CoinbaseDataPrefixV1 = {
    CoinbaseDataPrefixV1(chainIndex.from.value.toByte, chainIndex.to.value.toByte, blockTs)
  }
}

final case class CoinbaseDataPrefixV2 private (fromGroup: Byte, toGroup: Byte)

object CoinbaseDataPrefixV2 {
  implicit val prefixSerde: Serde[CoinbaseDataPrefixV2] =
    Serde.forProduct2(
      CoinbaseDataPrefixV2.apply,
      prefix => (prefix.fromGroup, prefix.toGroup)
    )

  def from(chainIndex: ChainIndex): CoinbaseDataPrefixV2 = {
    CoinbaseDataPrefixV2(chainIndex.from.value.toByte, chainIndex.to.value.toByte)
  }
}

sealed trait CoinbaseData extends Product with Serializable {
  def ghostUncleData: AVector[GhostUncleData]
  def minerData: ByteString
  def isGhostEnabled: Boolean = this match {
    case _: CoinbaseDataV1 => false
    case _                 => true
  }
}

final case class CoinbaseDataV1(
    prefix: CoinbaseDataPrefixV1,
    minerData: ByteString
) extends CoinbaseData {
  def ghostUncleData: AVector[GhostUncleData] = AVector.empty[GhostUncleData]
}

object CoinbaseDataV1 {
  implicit val serde: Serde[CoinbaseDataV1] = new Serde[CoinbaseDataV1] {
    def serialize(input: CoinbaseDataV1): ByteString = encode(input.prefix) ++ input.minerData

    def _deserialize(input: ByteString): SerdeResult[Staging[CoinbaseDataV1]] = {
      for {
        prefix <- _decode[CoinbaseDataPrefixV1](input)
      } yield Staging(CoinbaseDataV1(prefix.value, prefix.rest), ByteString.empty)
    }
  }
}

final case class GhostUncleData(blockHash: BlockHash, lockupScript: LockupScript.Asset)

object GhostUncleData {
  implicit val ghostUncleDataSerde: Serde[GhostUncleData] =
    Serde.forProduct2(GhostUncleData.apply, d => (d.blockHash, d.lockupScript))

  def from(uncle: SelectedGhostUncle): GhostUncleData =
    GhostUncleData(uncle.blockHash, uncle.lockupScript)
}

final case class CoinbaseDataV2(
    prefix: CoinbaseDataPrefixV1,
    ghostUncleData: AVector[GhostUncleData],
    minerData: ByteString
) extends CoinbaseData

object CoinbaseDataV2 {
  implicit val serde: Serde[CoinbaseDataV2] = new Serde[CoinbaseDataV2] {
    def serialize(input: CoinbaseDataV2): ByteString =
      encode(input.prefix) ++ encode(input.ghostUncleData) ++ input.minerData

    def _deserialize(input: ByteString): SerdeResult[Staging[CoinbaseDataV2]] = {
      for {
        prefix <- _decode[CoinbaseDataPrefixV1](input)
        uncles <- _decode[AVector[GhostUncleData]](prefix.rest)
      } yield Staging(CoinbaseDataV2(prefix.value, uncles.value, uncles.rest), ByteString.empty)
    }
  }
}

final case class CoinbaseDataV3(
    prefix: CoinbaseDataPrefixV2,
    ghostUncleData: AVector[GhostUncleData],
    minerData: ByteString
) extends CoinbaseData

object CoinbaseDataV3 {
  implicit val serde: Serde[CoinbaseDataV3] = new Serde[CoinbaseDataV3] {
    def serialize(input: CoinbaseDataV3): ByteString =
      encode(input.prefix) ++ encode(input.ghostUncleData) ++ input.minerData

    def _deserialize(input: ByteString): SerdeResult[Staging[CoinbaseDataV3]] = {
      for {
        prefix <- _decode[CoinbaseDataPrefixV2](input)
        uncles <- _decode[AVector[GhostUncleData]](prefix.rest)
      } yield Staging(CoinbaseDataV3(prefix.value, uncles.value, uncles.rest), ByteString.empty)
    }
  }
}

object CoinbaseData {
  implicit val serializer: Serializer[CoinbaseData] = {
    case data: CoinbaseDataV1 => encode(data)
    case data: CoinbaseDataV2 => encode(data)
    case data: CoinbaseDataV3 => encode(data)
  }

  def deserialize(input: ByteString, hardFork: HardFork): SerdeResult[CoinbaseData] = {
    if (hardFork.isDanubeEnabled()) {
      decode[CoinbaseDataV3](input)
    } else if (hardFork.isRhoneEnabled()) {
      decode[CoinbaseDataV2](input)
    } else {
      decode[CoinbaseDataV1](input)
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
    val hardFork = networkConfig.getHardFork(blockTs)
    if (hardFork.isDanubeEnabled()) {
      val prefixV2 = CoinbaseDataPrefixV2.from(chainIndex)
      CoinbaseDataV3(prefixV2, sortedGhostUncles.map(GhostUncleData.from), minerData)
    } else {
      val prefixV1 = CoinbaseDataPrefixV1.from(chainIndex, blockTs)
      if (hardFork.isRhoneEnabled()) {
        CoinbaseDataV2(prefixV1, sortedGhostUncles.map(GhostUncleData.from), minerData)
      } else {
        CoinbaseDataV1(prefixV1, minerData)
      }
    }
  }
}
