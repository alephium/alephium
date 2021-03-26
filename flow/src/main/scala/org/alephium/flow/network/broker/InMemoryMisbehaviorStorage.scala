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

import java.net.InetAddress

import scala.collection.mutable

import org.alephium.flow.network.broker.MisbehaviorManager._
import org.alephium.util.{discard, AVector, Duration, TimeStamp}

class InMemoryMisbehaviorStorage(val penaltyForgivness: Duration) extends MisbehaviorStorage {

  private val peers: mutable.Map[InetAddress, MisbehaviorStatus] = mutable.Map.empty

  def get(peer: InetAddress): Option[MisbehaviorStatus] = {
    peers.get(peer).flatMap { status => withUpdatedStatus(peer, status) { (_, status) => status } }
  }

  def update(peer: InetAddress, penalty: Penalty): Unit = {
    peers.addOne(peer -> penalty)
  }

  def ban(peer: InetAddress, until: TimeStamp): Unit = {
    peers.update(peer, Banned(until))
  }

  def isBanned(peer: InetAddress): Boolean = {
    get(peer) match {
      case Some(status) =>
        status match {
          case Banned(_)     => true
          case Penalty(_, _) => false
        }
      case None => false
    }
  }

  def remove(peer: InetAddress): Unit = {
    discard(peers.remove(peer))
  }

  def list(): AVector[Peer] = {
    AVector.from(peers.flatMap { case (peer, status) =>
      withUpdatedStatus(peer, status) { (peer, newStatus) => Peer(peer, newStatus) }
    })
  }

  private def withUpdatedStatus[A](peer: InetAddress, status: MisbehaviorStatus)(
      f: (InetAddress, MisbehaviorStatus) => A
  ): Option[A] = {
    status match {
      case Banned(until) if until < TimeStamp.now() =>
        peers.remove(peer)
        None
      case Penalty(_, ts) if TimeStamp.now().deltaUnsafe(ts) > penaltyForgivness =>
        peers.remove(peer)
        None
      case other =>
        Some(f(peer, other))
    }
  }
}
