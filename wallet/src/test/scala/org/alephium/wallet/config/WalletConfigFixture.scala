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

import org.alephium.api.model.ApiKey
import org.alephium.protocol.config.{GroupConfig, NetworkConfigFixture}
import org.alephium.util.{AlephiumSpec, AVector, Duration, SocketUtil}

trait WalletConfigFixture extends SocketUtil with NetworkConfigFixture.Default {

  val host: InetAddress = InetAddress.getByName("127.0.0.1")
  val blockFlowPort     = generatePort()
  val walletPort        = generatePort()

  val groupNum = 4

  val lockingTimeout = Duration.ofMinutesUnsafe(10)

  val blockflowFetchMaxAge = Duration.unsafe(1000)

  val tempSecretDir = Files.createTempDirectory("blockflow-wallet-spec")
  tempSecretDir.toFile.deleteOnExit
  AlephiumSpec.addCleanTask(() => AlephiumSpec.delete(tempSecretDir))

  implicit val groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = config.blockflow.groups
  }

  val apiKeys           = AVector.empty[ApiKey]
  val enableHttpMetrics = false

  lazy val config = WalletConfig(
    Some(walletPort),
    tempSecretDir,
    lockingTimeout,
    apiKeys,
    enableHttpMetrics,
    WalletConfig.BlockFlow(
      host.getHostAddress,
      blockFlowPort,
      groupNum,
      blockflowFetchMaxAge,
      apiKeys.headOption
    )
  )
}
