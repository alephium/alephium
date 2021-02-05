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

package org.alephium.flow.network.broker

import java.net.InetSocketAddress

import scala.collection.mutable

import org.alephium.flow.network.broker.MisbehaviorManager._
import org.alephium.util.{discard, AVector, TimeStamp}

class InMemoryMisbehaviorStorage extends MisbehaviorStorage {

  private val peers: mutable.Map[InetSocketAddress, MisbehaviorStatus] = mutable.Map.empty

  def get(peer: InetSocketAddress): Option[MisbehaviorStatus] = {
    peers.get(peer).map { status =>
      withUpdatedStatus(peer, status) { (_, status) =>
        status
      }
    }
  }

  def update(peer: InetSocketAddress, penalty: Penalty): Unit = {
    peers.addOne(peer -> penalty)
  }

  def ban(peer: InetSocketAddress, until: TimeStamp): Unit = {
    peers.update(peer, Banned(until))
  }

  def isBanned(peer: InetSocketAddress): Boolean = {
    get(peer) match {
      case Some(status) =>
        status match {
          case Banned(_)  => true
          case Penalty(_) => false
        }
      case None =>
        false
    }
  }

  def remove(peer: InetSocketAddress): Unit = {
    discard(peers.remove(peer))
  }

  def list(): AVector[Peer] = {
    AVector.from(peers.map {
      case (peer, status) =>
        withUpdatedStatus(peer, status) { (peer, newStatus) =>
          Peer(peer, newStatus)
        }
    })
  }

  private def withUpdatedStatus[A](peer: InetSocketAddress, status: MisbehaviorStatus)(
      f: (InetSocketAddress, MisbehaviorStatus) => A): A = {
    status match {
      case Banned(until) if until < TimeStamp.now() =>
        val newStatus = Penalty(0)
        peers.addOne(peer -> status)
        f(peer, newStatus)
      case other =>
        f(peer, other)
    }
  }
}
