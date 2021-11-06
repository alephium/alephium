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

package org.alephium.flow.setting

import java.nio.file.{Files => JFiles, Path, Paths}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.Hash
import org.alephium.util.{Env, Files}

object Platform extends StrictLogging {
  def getRootPath(): Path = getRootPath(Env.currentEnv)

  def getRootPath(env: Env): Path = {
    val rootPath = env match {
      case Env.Prod =>
        sys.env.get("ALEPHIUM_HOME") match {
          case Some(rawPath) => Paths.get(rawPath)
          case None          => Files.homeDir.resolve(".alephium/mainnet")
        }
      case Env.Debug =>
        Files.homeDir.resolve(s".alephium-${env.name}")
      case Env.Test =>
        Files.tmpDir.resolve(s".alephium-${env.name}-${Hash.random.toHexString}")
      case Env.Integration =>
        Files.tmpDir.resolve(s".alephium-${env.name}-${Hash.random.toHexString}")
    }
    if (!JFiles.exists(rootPath)) {
      Env.forProd(logger.info(s"Creating root path: $rootPath"))
      rootPath.toFile.mkdir()
    }
    rootPath
  }
}
