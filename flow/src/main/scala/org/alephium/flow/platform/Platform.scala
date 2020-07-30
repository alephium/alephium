package org.alephium.flow.platform

import java.nio.file.Path

import org.alephium.protocol.Hash
import org.alephium.util.{Env, Files}

object Platform {
  def generateRootPath(env: Env): Path = {
    env match {
      case Env.Prod =>
        Files.homeDir.resolve(".alephium")
      case Env.Debug =>
        Files.homeDir.resolve(s".alephium-${env.name}")
      case Env.Test =>
        Files.tmpDir.resolve(s".alephium-${env.name}-${Hash.random.toHexString}")
      case Env.Integration =>
        Files.tmpDir.resolve(s".alephium-${env.name}-${Hash.random.toHexString}")
    }
  }
}
