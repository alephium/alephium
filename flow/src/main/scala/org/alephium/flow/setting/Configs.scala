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

import java.io.File
import java.nio.file.Path

import scala.annotation.tailrec
import scala.util.control.Exception.allCatch

import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging

import org.alephium.protocol.config.{ConsensusConfig, GroupConfig}
import org.alephium.protocol.model.{Block, ChainIndex, NetworkType, Transaction}
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

  def getConfigTemplate(rootPath: Path, confName: String, templateName: String): File = {
    val file = getConfigFile(rootPath, confName)

    if (file.exists) file.delete()

    Files.copyFromResource(s"/$templateName.conf.tmpl", file.toPath)
    file.setWritable(false)

    file
  }

  def getConfigFile(rootPath: Path, name: String): File = {
    val path = rootPath.resolve(s"$name.conf")
    logger.info(s"Using $name configuration file at $path \n")

    path.toFile
  }

  def getConfigNetwork(rootPath: Path, networkType: NetworkType): File =
    getConfigTemplate(rootPath, "network", s"network_${networkType.name}")

  def getConfigSystem(rootPath: Path): File = {
    val env = Env.resolve().name
    getConfigTemplate(rootPath, "system", s"system_$env")
  }

  def getConfigUser(rootPath: Path): File = {
    val file = getConfigFile(rootPath, "user")
    if (!file.exists) { file.createNewFile }
    file
  }

  def parseConfig(rootPath: Path, networkType: Option[NetworkType]): Config = {
    networkType match {
      case Some(networkType) =>
        ConfigFactory
          .parseFile(getConfigUser(rootPath))
          .withFallback(
            ConfigFactory
              .parseFile(getConfigNetwork(rootPath, networkType))
              .withFallback(ConfigFactory.parseFile(getConfigSystem(rootPath)))
          )
          .resolve()

      case None =>
        ConfigFactory
          .parseFile(getConfigUser(rootPath))
          .withFallback(ConfigFactory.parseFile(getConfigSystem(rootPath)))
          .resolve()
    }
  }

  def parseConfigAndValidate(rootPath: Path, networkType: Option[NetworkType]): Config = {
    val config = parseConfig(rootPath, networkType)
    if (!config.hasPath("alephium.discovery.bootstrap")) {
      logger.error(s"""|The bootstrap nodes are not defined!
                       |
                       |Please set the bootstrap nodes in $rootPath/user.conf and try again.
                       |
                       |Example:
                       |alephium.discovery.bootstrap = ["1.2.3.4:1234"]
                  """.stripMargin)
      sys.exit(1)
    } else {
      config
    }
  }

  def parseNetworkType(rootPath: Path): Option[NetworkType] = {
    val config = parseConfig(rootPath, None)
    if (!config.hasPath("alephium.chains.network-type")) {
      logger.error(s"""|The network type isn't defined!
                       |
                       |Please set the network type in your $rootPath/user.conf and try again.
                       |
                       |Example:
                       |alephium.chains.network-type = "testnet"
                  """.stripMargin)
      sys.exit(1)
    } else {
      Option(config.getString("alephium.chains.network-type")).flatMap(NetworkType.fromName)
    }
  }

  def splitBalance(raw: String): Option[(LockupScript, U64)] = {
    val splitIndex = raw.indexOf(":")
    if (splitIndex == -1) None
    else {
      val left  = raw.take(splitIndex)
      val right = raw.drop(splitIndex + 1)
      for {
        bytestring   <- Hex.from(left)
        lockupScript <- deserialize[LockupScript](bytestring).toOption
        rawBalance   <- allCatch.opt(BigInt(right).underlying())
        balance      <- U64.from(rawBalance)
      } yield (lockupScript, balance)
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
