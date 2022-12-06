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

import scopt.OParser

import org.alephium.api.UtilJson.*
import org.alephium.api.model.CompileProjectResult
import org.alephium.json.Json.*
import org.alephium.protocol.BuildInfo
import org.alephium.util.AVector

@SuppressWarnings(
  Array(
    "org.wartremover.warts.ToString",
    "org.wartremover.warts.PublicInference",
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.Serializable"
  )
)
final case class Cli() {
  import Codec.*

  private var config: Config = Config()
  private val builder        = OParser.builder[Config]
  private val parser = {
    import builder.*
    OParser.sequence(
      programName("ralphc"),
      head("Ralph language compiler", BuildInfo.version),
      opt[String]('c', "contracts")
        .validate(path => {
          if (!new File(path).isDirectory) {
            Left("contracts is not directory")
          } else {
            Right(())
          }
        })
        .action((path, c) => c.copy(contracts = path))
        .text("Contracts path, default: contracts"),
      opt[String]('a', "artifacts")
        .action((a, c) => c.copy(artifacts = a))
        .text("Artifacts path, default: artifacts"),
      opt[Unit]('w', "warning")
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
      version('v', "version").text("Print version information")
    )
  }

  def call(args: Array[String]): Int = {
    val compiler  = Compiler()
    var arguments = args
    if (arguments.isEmpty) {
      arguments = arguments :+ "-h"
    }
    OParser.parse(parser, arguments, config) match {
      case Some(c) =>
        config = c
        debug(write(config, 2))
        compiler
          .compileProject(config)
          .fold(
            err => error(err, config.contractsPath().toFile.getPath),
            ret => result(ret)
          )
      case _ =>
        -1
    }
  }

  private def debug[O](values: O*): Unit = {
    if (config.debug) {
      values.foreach(value => {
        print(value)
        print("\n")
      })
    }
  }

  private def error[T, O](msg: T, other: O): Int = {
    print(s"error: \n $msg \n")
    print(other)
    print("\n")
    -1
  }

  private def warning[T, O](msg: T, other: O): Int = {
    print(s"\n $msg")
    print(s"$other \n")
    if (config.warningAsError) {
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
    ret.scripts.foreach(script => each(script.warnings, s"Script name: ${script.name}, waring:"))
    ret.contracts.foreach(contract =>
      each(contract.warnings, s"Contract name: ${contract.name}, waring:")
    )
    checkWarningAsError
  }
}
