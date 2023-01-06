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

import java.nio.file.{Files, Path}

import scala.collection.immutable.ArraySeq

import scopt.OParser

import org.alephium.api.model.CompileProjectResult
import org.alephium.json.Json._
import org.alephium.protocol.BuildInfo
import org.alephium.util.AVector

// scalastyle:off regex
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
          |  $ java -jar ralphc.jar -c <contracts1>,<contracts2>... -a <artifacts1>,<artifacts2>... [options]
          |""".stripMargin.trim
      ),
      note("\nOptions:"),
      opt[Seq[Path]]('c', "contracts")
        .validate(validateFolders)
        .action((path, c) => c.copy(contracts = ArraySeq.from(path)))
        .text("Contract folder, default: ./contracts"),
      opt[Seq[Path]]('a', "artifacts")
        .action((a, c) => c.copy(artifacts = ArraySeq.from(a)))
        .text("Artifact folder, default: ./artifacts"),
      opt[Unit]('w', "werror")
        .action((_, c) => c.copy(warningAsError = true))
        .text("Treat warnings as errors"),
      opt[Unit]("ic")
        .action((_, c) => c.copy(ignoreUnusedConstantsWarnings = true))
        .text("Ignore unused constant warnings"),
      opt[Unit]("iv")
        .action((_, c) => c.copy(ignoreUnusedVariablesWarnings = true))
        .text("Ignore unused variable warnings"),
      opt[Unit]("if")
        .action((_, c) => c.copy(ignoreUnusedFieldsWarnings = true))
        .text("Ignore unused field warnings"),
      opt[Unit]("ir")
        .action((_, c) => c.copy(ignoreUpdateFieldsCheckWarnings = true))
        .text("Ignore update field check warnings"),
      opt[Unit]("ip")
        .action((_, c) => c.copy(ignoreUnusedPrivateFunctionsWarnings = true))
        .text("Ignore unused private functions warnings"),
      opt[Unit]("ie")
        .action((_, c) => c.copy(ignoreCheckExternalCallerWarnings = true))
        .text("Ignore check external caller warnings"),
      opt[Unit]('d', "debug")
        .action((_, c) => c.copy(debug = true))
        .text("Debug mode"),
      help('h', "help").text("Print this usage text"),
      version('v', "version").text("Print version information"),
      checkConfig(configs => {
        if (configs.contracts.length != configs.artifacts.length) {
          Left("The number of contract folders and artifact folders is not equal")
        } else if (configs.contracts.distinct.length != configs.contracts.length) {
          Left("The contract folders are not distinct")
        } else if (configs.artifacts.distinct.length != configs.artifacts.length) {
          Left("The artifact folders are not distinct")
        } else {
          Right(())
        }
      })
    )
  }

  def validateFolders(paths: Seq[Path]): Either[String, Unit] = {
    paths
      .find(!Files.isDirectory(_))
      .fold[Either[String, Unit]](Right(()))(path =>
        Left(s"Expected directory, got file: <${path}>")
      )
  }

  def call(args: Array[String]): Int = {
    OParser.parse(parser, args, configs) match {
      case Some(c: Configs) =>
        configs = c
        debug(write(configs, 2))
        c.configs()
          .map(config =>
            Compiler(config)
              .compileProject()
              .fold(
                err => error(config.contractPath, err),
                ret => result(ret)
              )
          )
          .sum
      case _ =>
        -1
    }
  }

  private def debug(message: String): Unit = {
    if (configs.debug) {
      println(s"$message")
    }
  }

  private def error(path: Path, err: String): Int = {
    println(s"""Error while compiling contracts in the folder: <${path}>
               |$err""".stripMargin)
    -1
  }

  private def warning(name: String, warnings: AVector[String]): Int = {
    val sep = "\n  - "
    println(s"""Warning ($name):${warnings.mkString(sep, sep, "")}""")
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
        checkWarningAsError = warning(name, warnings)
      }
    }
    ret.scripts.foreach(script => each(script.warnings, s"Script `${script.name}`"))
    ret.contracts.foreach(contract => each(contract.warnings, s"Contract `${contract.name}`"))
    checkWarningAsError
  }
}
