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

package org.alephium.api.model

import org.alephium.api.{badRequest, Try}
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, ChainIndex, GroupIndex}
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class CallContract(
    group: Int,
    worldStateBlockHash: Option[BlockHash] = None,
    txId: Option[Hash] = None,
    address: Address.Contract,
    methodIndex: Int,
    args: Option[AVector[Val]] = None,
    existingContracts: Option[AVector[Address.Contract]] = None,
    inputAssets: Option[AVector[TestInputAsset]] = None
) {
  def validate()(implicit brokerConfig: BrokerConfig): Try[ChainIndex] = {
    for {
      _ <-
        if (GroupIndex.validate(group)) {
          Right(())
        } else {
          Left(badRequest(s"Invalid group $group"))
        }
      chainIndex = ChainIndex.unsafe(group, group)
      _ <-
        if (worldStateBlockHash.map(ChainIndex.from).getOrElse(chainIndex) != chainIndex) {
          Left(badRequest(s"Invalid block hash $worldStateBlockHash"))
        } else {
          Right(())
        }
    } yield chainIndex
  }
}
