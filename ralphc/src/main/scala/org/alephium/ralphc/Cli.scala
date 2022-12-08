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

package org.alephium.ralphc

import java.io.File

import scala.collection.immutable.ArraySeq

import scopt.OParser

import org.alephium.api.UtilJson._
import org.alephium.api.model.CompileProjectResult
import org.alephium.json.Json._
import org.alephium.protocol.BuildInfo
import org.alephium.util.AVector

// scalastyle:off
@SuppressWarnings(
  Array(
    "org.wartremover.warts.ToString",
    "org.wartremover.warts.PublicInference",
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Serializable"
  )
)
final case class Cli() {
  import Codec._
  var configs: Configs = Configs()
  private val builder  = OParser.builder[Configs]
  private val parser = {
    import builder.*
    OParser.sequence(
      programName("ralphc"),
      head("ralphc", BuildInfo.version),
      note(
        """
          |Examples:
          |# To compile a single project contract
          |Linux or MacOs:
          |  $ java -jar ralphc.jar -c ./project/contracts, -a ./project/artifacts
          |
          |# To compile two project contracts
          |Linux or MacOs:
          |  $ java -jar ralphc.jar -c ./project1/contracts,./project2/contracts -a ./project1/artifacts,./project2/artifacts
          |
          |# To compile multi-project contracts
          |Linux or MacOs:
          |  $ java -jar ralphc.jar -c ./project1/contracts,./project2/contracts,./project3/contracts,... -a ./project1/artifacts,./project2/artifacts,./project3/artifacts,...
          |
          |""".stripMargin
      ),
      opt[Seq[String]]('c', "contracts")
        .validate(
          _.find(!new File(_).isDirectory).fold[Either[String, Unit]](Right(()))(path =>
            Left(s"${path} is not directory")
          )
        )
        .action((path, c) => c.copy(contracts = ArraySeq.from(path)))
        .text("Contracts path, default: contracts"),
      opt[Seq[String]]('a', "artifacts")
        .action((a, c) => c.copy(artifacts = ArraySeq.from(a)))
        .text("Artifacts path, default: artifacts"),
      opt[Unit]('w', "werror")
        .action((_, c) => c.copy(warningAsError = true))
        .text("Consider warning as error"),
      opt[Unit]("ic")
        .action((_, c) => c.copy(ignoreUnusedConstantsWarnings = true))
        .text("Ignore unused constant warning"),
      opt[Unit]("iv")
        .action((_, c) => c.copy(ignoreUnusedVariablesWarnings = true))
        .text("Ignore unused variable warning"),
      opt[Unit]("if")
        .action((_, c) => c.copy(ignoreUnusedFieldsWarnings = true))
        .text("Ignore unused field warning"),
      opt[Unit]("ir")
        .action((_, c) => c.copy(ignoreUpdateFieldsCheckWarnings = true))
        .text("Ignore update field check warning"),
      opt[Unit]("ip")
        .action((_, c) => c.copy(ignoreUnusedPrivateFunctionsWarnings = true))
        .text("Ignore unused private functions warning"),
      opt[Unit]("ie")
        .action((_, c) => c.copy(ignoreExternalCallCheckWarnings = true))
        .text("Ignore external call check warning"),
      opt[Unit]('d', "debug")
        .action((_, c) => c.copy(debug = true))
        .text("Debug mode"),
      help('h', "help").text("Print help information (use `--help` for more detail)"),
      version('v', "version").text("Print version information"),
      checkConfig(configs => {
        if (configs.contracts.length != configs.artifacts.length) {
          Left("contracts and artifacts path mismatch")
        } else if (configs.contracts.distinct.length != configs.contracts.length) {
          Left("contracts are no duplicated")
        } else if (configs.artifacts.distinct.length != configs.artifacts.length) {
          Left("artifacts are no duplicated")
        } else {
          Right(())
        }
      })
    )
  }

  def call(args: Array[String]): Int = {
    var arguments = args
    if (arguments.isEmpty) {
      arguments = arguments :+ "-h"
    }
    OParser.parse(parser, arguments, configs) match {
      case Some(c: Configs) =>
        configs = c
        debug(write(configs, 2))
        c.configs()
          .map(config =>
            Compiler(config)
              .compileProject()
              .fold(
                err => error(s"contracts path: ${config.contractsPath().toFile.getPath}", err),
                ret => result(ret)
              )
          )
          .sum
      case _ =>
        -1
    }
  }

  private def debug(values: String*): Unit = {
    if (configs.debug) {
      values.foreach(println)
    }
  }

  private def error(path: String, err: String): Int = {
    println(s"""error:
               |${path}
               |$err""".stripMargin)
    -1
  }

  private def warning(name: String, warningMessages: String): Int = {
    println(s"""${name}
               |warning:
               |${warningMessages}""".stripMargin)
    if (configs.warningAsError) {
      -1
    } else {
      0
    }
  }

  private def result(ret: CompileProjectResult): Int = {
    var checkWarningAsError = 0
    val each = (warnings: AVector[String], name: String) => {
      if (warnings.nonEmpty) {
        checkWarningAsError = warning(name, write(warnings, 2))
      }
    }
    ret.scripts.foreach(script => each(script.warnings, s"Script name: ${script.name}"))
    ret.contracts.foreach(contract => each(contract.warnings, s"Contract name: ${contract.name}"))
    checkWarningAsError
  }
}
