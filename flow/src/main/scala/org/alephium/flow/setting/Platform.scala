package org.alephium.flow.setting

import java.nio.file.{Files => JFiles, Path, Paths}

import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.Hash
import org.alephium.util.{Env, Files}

object Platform extends StrictLogging {
  def getRootPath(): Path = getRootPath(Env.resolve())

  def getRootPath(env: Env): Path = {
    val rootPath = env match {
      case Env.Prod =>
        sys.env.get("ALEPHIUM_HOME").fold[Path](Files.homeDir.resolve(".alephium"))(Paths.get(_))
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
