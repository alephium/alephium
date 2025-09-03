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

package org.alephium.api.endpoints

import java.net.InetAddress

import sttp.tapir._
import sttp.tapir.generic.auto._

import org.alephium.api.Endpoints._
import org.alephium.api.UtilJson.{avectorReadWriter, inetAddressRW}
import org.alephium.api.model.{Address => _, _}
import org.alephium.protocol.model.{Balance => _, Transaction => _, _}
import org.alephium.util.AVector

trait InfosEndpoints extends BaseEndpoints {
  private val infosEndpoint: BaseEndpoint[Unit, Unit] =
    baseEndpoint
      .in("infos")
      .tag("Infos")

  val getNodeInfo: BaseEndpoint[Unit, NodeInfo] =
    infosEndpoint.get
      .in("node")
      .out(jsonBody[NodeInfo])
      .summary("Get info about that node")

  val getNodeVersion: BaseEndpoint[Unit, NodeVersion] =
    infosEndpoint.get
      .in("version")
      .out(jsonBody[NodeVersion])
      .summary("Get version about that node")

  val getChainParams: BaseEndpoint[Unit, ChainParams] =
    infosEndpoint.get
      .in("chain-params")
      .out(jsonBody[ChainParams])
      .summary("Get key params about your blockchain")

  val getSelfClique: BaseEndpoint[Unit, SelfClique] =
    infosEndpoint.get
      .in("self-clique")
      .out(jsonBody[SelfClique])
      .summary("Get info about your own clique")

  val getSelfCliqueSynced: BaseEndpoint[Unit, Boolean] =
    infosEndpoint.get
      .in("self-clique-synced")
      .out(jsonBody[Boolean])
      .summary("Is your clique synced?")

  val getInterCliquePeerInfo: BaseEndpoint[Unit, AVector[InterCliquePeerInfo]] =
    infosEndpoint.get
      .in("inter-clique-peer-info")
      .out(jsonBody[AVector[InterCliquePeerInfo]])
      .summary("Get infos about the inter cliques")

  val getDiscoveredNeighbors: BaseEndpoint[Unit, AVector[BrokerInfo]] =
    infosEndpoint.get
      .in("discovered-neighbors")
      .out(jsonBody[AVector[BrokerInfo]])
      .summary("Get discovered neighbors")

  val getMisbehaviors: BaseEndpoint[Unit, AVector[PeerMisbehavior]] =
    infosEndpoint.get
      .in("misbehaviors")
      .out(jsonBody[AVector[PeerMisbehavior]])
      .summary("Get the misbehaviors of peers")

  val misbehaviorAction: BaseEndpoint[MisbehaviorAction, Unit] =
    infosEndpoint.post
      .in("misbehaviors")
      .in(jsonBody[MisbehaviorAction])
      .summary("Ban/Unban given peers")

  val getUnreachableBrokers: BaseEndpoint[Unit, AVector[InetAddress]] =
    infosEndpoint.get
      .in("unreachable")
      .out(jsonBody[AVector[InetAddress]])
      .summary("Get the unreachable brokers")

  val discoveryAction: BaseEndpoint[DiscoveryAction, Unit] =
    infosEndpoint.post
      .in("discovery")
      .in(jsonBody[DiscoveryAction])
      .summary("Set brokers to be unreachable/reachable")

  val getHistoryHashRate: BaseEndpoint[TimeInterval, HashRateResponse] =
    infosEndpoint.get
      .in("history-hashrate")
      .in(timeIntervalQuery)
      .out(jsonBody[HashRateResponse])
      .summary("Get history average hashrate on the given time interval")

  val getCurrentHashRate: BaseEndpoint[Option[TimeSpan], HashRateResponse] =
    infosEndpoint.get
      .in("current-hashrate")
      .in(query[Option[TimeSpan]]("timespan"))
      .out(jsonBody[HashRateResponse])
      .summary("Get average hashrate from `now - timespan(millis)` to `now`")

  val getCurrentDifficulty: BaseEndpoint[Unit, CurrentDifficulty] =
    infosEndpoint.get
      .in("current-difficulty")
      .out(jsonBody[CurrentDifficulty])
      .summary("Get the average difficulty of the latest blocks from all shards")
}
