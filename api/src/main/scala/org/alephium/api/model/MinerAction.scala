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

sealed trait MinerAction
object MinerAction {
  case object StartMining extends MinerAction
  case object StopMining  extends MinerAction

  def validate(str: String): Option[MinerAction] =
    str match {
      case "start-mining" => Some(MinerAction.StartMining)
      case "stop-mining"  => Some(MinerAction.StopMining)
      case _              => None
    }

  def write(minerAction: MinerAction): String =
    minerAction match {
      case MinerAction.StartMining => "start-mining"
      case MinerAction.StopMining  => "stop-mining"
    }
}
