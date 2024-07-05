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
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Address, BlockHash, ChainIndex, GroupIndex}
import org.alephium.util.AVector

trait CallBase {
  def group: Int
  def fromAddress: Option[Address]
  def worldStateBlockHash: Option[BlockHash]
  def allContractAddresses: AVector[Address.Contract]

  final def validate()(implicit brokerConfig: BrokerConfig): Try[GroupIndex] = {
    for {
      _ <-
        if (GroupIndex.validate(group)) {
          Right(())
        } else {
          Left(badRequest(s"Invalid group $group"))
        }
      _ <- fromAddress match {
        case Some(address) =>
          val addressGroup = address.groupIndex
          if (addressGroup.value == group) {
            Right(())
          } else {
            Left(
              badRequest(
                s"Group mismatch: provided group is $group; group for $address is ${addressGroup.value}"
              )
            )
          }
        case None => Right(())
      }
      chainIndex = ChainIndex.unsafe(group, group)
      _ <- worldStateBlockHash match {
        case Some(blockHash) =>
          if (ChainIndex.from(blockHash) == chainIndex) {
            Right(())
          } else {
            Left(badRequest(s"Invalid block hash ${blockHash.toHexString}"))
          }
        case None => Right(())
      }
    } yield chainIndex.from
  }
}
