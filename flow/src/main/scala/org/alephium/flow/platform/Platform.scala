package org.alephium.flow.platform

import java.nio.file.{Files => JFiles, Path}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.Hash
import org.alephium.util.{Env, Files}

object Platform extends StrictLogging {
  def getRootPath(): Path = getRootPath(Env.resolve())

  def getRootPath(env: Env): Path = {
    val rootPath = env match {
      case Env.Prod =>
        Files.homeDir.resolve(".alephium")
      case Env.Debug =>
        Files.homeDir.resolve(s".alephium-${env.name}")
      case Env.Test =>
        Files.tmpDir.resolve(s".alephium-${env.name}-${Hash.random.toHexString}")
      case Env.Integration =>
        Files.tmpDir.resolve(s".alephium-${env.name}-${Hash.random.toHexString}")
    }
    if (!JFiles.exists(rootPath)) {
      logger.info(s"Creating root path: $rootPath")
      rootPath.toFile.mkdir()
    }
    rootPath
  }
}
