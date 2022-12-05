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
object Main extends App {
  import scopt.OParser
  import Codec._

  var config  = Config()
  val builder = OParser.builder[Config]
  val parser = {
    import builder.*
    OParser.sequence(
      programName("ralphc"),
      head("Ralph language compiler", BuildInfo.version),
      opt[File]('p', "contracts")
        .action((path, c) => c.copy(contracts = path))
        .text("Contracts path, default: contracts"),
      opt[File]('a', "artifacts")
        .action((name, c) => c.copy(artifacts = name))
        .text("Artifacts directory name, default: artifacts"),
      opt[Unit]('w', "warning")
        .action((_, c) => c.copy(warningAsError = true))
        .text("Ignore external call check warning"),
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
  var arguments = args
  if (arguments.isEmpty) {
    arguments = arguments :+ "-h"
  }
  OParser.parse(parser, arguments, config) match {
    case Some(c) =>
      config = c
      debug(
        s"contractsPath: ${config.contracts.getPath}",
        s"artifacts: ${config.artifacts.getPath}",
        s"warningAsError: ${config.warningAsError}",
        s"compilerOptions: ${write(config.compilerOptions(), 2)}"
      )

      Compiler
        .compileProject(config)
        .fold(
          err => System.exit(error(err, config.contractsPath().toFile.getPath)),
          ret => System.exit(result(ret))
        )
    case _ =>
      print("arguments are bad!\n")
      System.exit(-1)
  }

  def debug[O](values: O*): Unit = {
    if (config.debug) {
      values.foreach(value => {
        print(value)
        print("\n")
      })
    }
  }

  def error[T, O](msg: T, other: O): Int = {
    print(s"error: \n $msg \n")
    print(other)
    print("\n")
    -1
  }

  def warning[T, O](msg: T, other: O): Int = {
    print(s"\n $msg")
    print(s"$other \n")
    if (config.warningAsError) {
      -1
    } else {
      0
    }
  }

  def result(ret: CompileProjectResult): Int = {
    var checkWaringAsError = 0
    val each = (warnings: AVector[String], name: String) => {
      if (warnings.nonEmpty) {
        checkWaringAsError = warning(name, write(warnings, 2))
      }
    }
    ret.scripts.foreach(script => each(script.warnings, s"Script name: ${script.name}, waring:"))
    ret.contracts.foreach(contract =>
      each(contract.warnings, s"Contract name: ${contract.name}, waring:")
    )
    checkWaringAsError
  }
}
