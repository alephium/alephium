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

package org.alephium.wallet.config

import java.net.InetAddress
import java.nio.file.Files

import akka.testkit.SocketUtil

import org.alephium.protocol.model.NetworkType
import org.alephium.util.Duration
import org.alephium.wallet.config.WalletConfig

trait WalletConfigFixture {

  val localhost: InetAddress = InetAddress.getLocalHost
  val blockFlowPort          = SocketUtil.temporaryLocalPort(SocketUtil.Both)
  val walletPort             = SocketUtil.temporaryLocalPort(SocketUtil.Both)

  val groupNum = 4

  val networkType = NetworkType.Mainnet

  val blockflowFetchMaxAge = Duration.unsafe(1000)

  val tempSecretDir = Files.createTempDirectory("blockflow-wallet-spec")
  tempSecretDir.toFile.deleteOnExit

  val config = WalletConfig(
    walletPort,
    tempSecretDir,
    networkType,
    WalletConfig.BlockFlow(localhost.getHostAddress, blockFlowPort, groupNum, blockflowFetchMaxAge))
}
