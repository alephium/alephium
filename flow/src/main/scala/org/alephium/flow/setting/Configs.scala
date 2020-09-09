package org.alephium.flow.setting

import java.io.File
import java.nio.file.Path

import scala.annotation.tailrec
import scala.util.control.Exception.allCatch

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.deserialize
import org.alephium.util._

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Configs extends StrictLogging {
  private def check(port: Int): Boolean = {
    port > 0x0400 && port <= 0xFFFF
  }

  def validatePort(port: Int): Either[String, Unit] = {
    if (check(port)) Right(()) else Left(s"Invalid port: $port")
  }

  def validatePort(portOpt: Option[Int]): Either[String, Unit] = {
    portOpt match {
      case Some(port) => validatePort(port)
      case None       => Right(())
    }
  }

  def getConfigFile(rootPath: Path, name: String): File = {
    val path = rootPath.resolve(s"$name.conf")
    logger.info(s"Using $name configuration file at $path \n")

    path.toFile
  }

  def getConfigSystem(rootPath: Path): File = {
    val file     = getConfigFile(rootPath, "system")
    val env      = Env.resolve()
    val filename = s"system_${env.name}.conf"
    if (!file.exists) {
      Files.copyFromResource(s"/$filename.tmpl", file.toPath)
      file.setWritable(false)
    }
    file
  }

  def getConfigUser(rootPath: Path): File = {
    val file = getConfigFile(rootPath, "user")
    if (!file.exists) { file.createNewFile }
    file
  }

  def parseConfig(rootPath: Path): Config = {
    ConfigFactory
      .parseFile(getConfigUser(rootPath))
      .withFallback(ConfigFactory.parseFile(getConfigSystem(rootPath)))
      .resolve()
  }

  def splitBalance(raw: String): Option[(LockupScript, U64)] = {
    val splitIndex = raw.indexOf(":")
    if (splitIndex == -1) None
    else {
      val left  = raw.take(splitIndex)
      val right = raw.drop(splitIndex + 1)
      for {
        address    <- LockupScript.fromBase58(left)
        rawBalance <- allCatch.opt(BigInt(right).underlying())
        balance    <- U64.from(rawBalance)
      } yield (address, balance)
    }
  }

  def loadBlockFlow(balances: AVector[(LockupScript, U64)])(
      implicit groupConfig: GroupConfig,
      consensusConfig: ConsensusConfig): AVector[AVector[Block]] = {
    AVector.tabulate(groupConfig.groups, groupConfig.groups) {
      case (from, to) =>
        val transactions = if (from == to) {
          val balancesOI  = balances.filter(_._1.groupIndex.value == from)
          val transaction = Transaction.genesis(balancesOI)
          AVector(transaction)
        } else AVector.empty[Transaction]
        mineGenesis(ChainIndex.from(from, to).get, transactions)
    }
  }

  private def mineGenesis(chainIndex: ChainIndex, transactions: AVector[Transaction])(
      implicit groupConfig: GroupConfig,
      consensusConfig: ConsensusConfig): Block = {
    @tailrec
    def iter(nonce: BigInt): Block = {
      val block = Block.genesis(transactions, consensusConfig.maxMiningTarget, nonce)
      // Note: we do not validate difficulty target here
      if (block.chainIndex == chainIndex) block else iter(nonce + 1)
    }

    iter(0)
  }
}
