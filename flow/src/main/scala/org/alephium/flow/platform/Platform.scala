package org.alephium.flow.platform

import java.nio.file.Path

import org.alephium.util.{Env, Files}

object Platform {
  def getRootPath(env: Env): Path = {
    env match {
      case Env.Prod =>
        Files.homeDir.resolve(".alephium")
      case Env.Debug =>
        Files.homeDir.resolve(s".alephium-${env.name}")
      case Env.Test =>
        Files.tmpDir.resolve(s".alephium-${env.name}")
      case Env.Integration =>
        Files.tmpDir.resolve(s".alephium-${env.name}")
    }
  }
}
