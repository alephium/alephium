package org.alephium.flow

import java.time.Duration
import java.io.File
import java.nio.file.{Path, Paths}

import com.typesafe.config.{Config, ConfigFactory}
import org.alephium.protocol.model.Block
import org.alephium.util.{AVector, Files}

object PlatformConfig {
  val rootPath = Paths.get(System.getProperty("user.home"), ".alephium")

  def getNoncesFilePath(groups: Int): Path = rootPath.resolve(s"nonces-$groups.conf")

  def getNoncesFile(groups: Int): Option[File] = {
    val path = getNoncesFilePath(groups)
    val file = path.toFile
    if (file.exists) Some(file)
    else None
  }

  def getUserFile(): File = {
    val directory = rootPath.toFile
    if (!directory.exists) directory.mkdir()

    val path = rootPath.resolve("user.conf")
    val file = path.toFile
    if (!file.exists) Files.copyFromResource("/user.conf.tmpl", path)

    file
  }

  def load(): PlatformConfig = {
    val user = ConfigFactory.parseFile(getUserFile)
    val main = user.withFallback(ConfigFactory.load())

    val config = getNoncesFile(main.getInt("alephium.groups")).fold(main)(file =>
      main.withFallback(ConfigFactory.parseFile(file)))

    new PlatformConfig(config.getConfig("alephium"))
  }

  object Default {
    val config: PlatformConfig = PlatformConfig.load()
  }

  trait Default {
    implicit def config: PlatformConfig = Default.config
  }
}

class PlatformConfig(val underlying: Config) { self =>
  val port: Int               = underlying.getInt("port")
  val pingFrequency: Duration = underlying.getDuration("pingFrequency")
  val groups: Int             = underlying.getInt("groups")
  val nonceStep: BigInt       = underlying.getInt("nonceStep")
  val retryTimeout: Duration  = underlying.getDuration("retryTimeout")

  val chainNum: Int = groups * groups

  def loadBlockFlow(groups: Int): AVector[AVector[Block]] = {
    val nonces = underlying.getStringList("nonces")
    assert(nonces.size == self.groups * self.groups)

    AVector.tabulate(groups, groups) {
      case (from, to) =>
        val index = from * self.groups + to
        val nonce = nonces.get(index)
        Block.genesis(AVector.empty, constant.Consensus.maxMiningTarget, BigInt(nonce))
    }
  }

  lazy val blocksForFlow: AVector[AVector[Block]] = loadBlockFlow(groups)
}
