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

import java.util.concurrent.Callable

import picocli.CommandLine.{Command, Option}

import org.alephium.api.UtilJson.*
import org.alephium.api.model.CompileProjectResult
import org.alephium.json.Json.*
import org.alephium.ralph.CompilerOptions
import org.alephium.util.AVector

// scalastyle:off
@SuppressWarnings(
  Array("org.wartremover.warts.JavaSerializable", "org.wartremover.warts.Serializable")
)
@Command(
  name = "ralphc",
  mixinStandardHelpOptions = true,
  version = Array("ralphc 1.5.4"),
  description = Array("ralph language compiler")
)
class Cli extends Callable[Int] {

  @Option(names = Array("-f"))
  val files: Array[String] = Array.empty

  @Option(names = Array("-d", "--debug"), defaultValue = "false", description = Array("Debug mode"))
  var debug: Boolean = false

  @Option(
    names = Array("--ic"),
    defaultValue = "false",
    description = Array("Ignore unused constants warning")
  )
  var ignoreUnusedConstantsWarnings: Boolean = false

  @Option(
    names = Array("--iv"),
    defaultValue = "false",
    description = Array("Ignore unused variables warning")
  )
  var ignoreUnusedVariablesWarnings: Boolean = false

  @Option(
    names = Array("--if"),
    defaultValue = "false",
    description = Array("Ignore unused fields warning")
  )
  var ignoreUnusedFieldsWarnings: Boolean = false

  @Option(
    names = Array("--ir"),
    defaultValue = "false",
    description = Array("Ignore update field check warning")
  )
  var ignoreUpdateFieldsCheckWarnings: Boolean = false

  @Option(
    names = Array("--ip"),
    defaultValue = "false",
    description = Array("Ignore unused private functions warning")
  )
  var ignoreUnusedPrivateFunctionsWarnings: Boolean = false

  @Option(
    names = Array("--ie"),
    defaultValue = "false",
    description = Array("Ignore external call check warning")
  )
  var ignoreExternalCallCheckWarnings: Boolean = false

  @Option(
    names = Array("-w", "--warning"),
    defaultValue = "false",
    description = Array("Consider warnings as errors")
  )
  var warningAsError: Boolean = false

  @Option(
    names = Array("-p", "--project"),
    defaultValue = "contracts",
    description = Array("Project path")
  )
  var projectDir: String = "contracts"

  @Option(
    names = Array("-a", "--artifacts"),
    defaultValue = "artifacts",
    description = Array("Artifacts directory name")
  )
  var artifacts = "artifacts"

  def debug[O](values: O*): Unit = {
    if (debug) {
      values.foreach(println(_))
    }
  }

  def error[T, O](msg: T, other: O): Int = {
    println(other)
    println(s"error: \n $msg \n")
    -1
  }

  def warning[T, O](msg: T, other: O): Int = {
    println(other)
    println(s"warning: \n $msg \n")
    if (warningAsError) {
      -1
    } else {
      0
    }
  }

  def ok[T, O](msg: T, other: O): Int = {
    println(other)
    println(msg)
    0
  }

  def compilerOptions(): CompilerOptions = CompilerOptions(
    ignoreUnusedConstantsWarnings = ignoreUnusedConstantsWarnings,
    ignoreUnusedVariablesWarnings = ignoreUnusedVariablesWarnings,
    ignoreUnusedFieldsWarnings = ignoreUnusedFieldsWarnings,
    ignoreUpdateFieldsCheckWarnings = ignoreUpdateFieldsCheckWarnings,
    ignoreUnusedPrivateFunctionsWarnings = ignoreUnusedPrivateFunctionsWarnings,
    ignoreExternalCallCheckWarnings = ignoreExternalCallCheckWarnings
  )

  override def call(): Int = {
    debug(
      compilerOptions(),
      s"projectDir: $projectDir",
      s"warningAsError: $warningAsError",
      s"files: ${files.mkString(",")}"
    )
    if (projectDir.nonEmpty) {
      Compiler
        .compileProject(projectDir, artifacts, compilerOptions())
        .fold(
          err => error(err.detail, projectDir),
          ret => result(ret)
        )
    } else {
      -1
    }
  }

  def result(ret: CompileProjectResult): Int = {
    var checkWaringAsError = 0
    val each = (warnings: AVector[String], name: String) => {
      if (warnings.nonEmpty) {
        warning(write(warnings, 2), name)
        checkWaringAsError -= 1
      }
    }

    ret.scripts.foreach(script => each(script.warnings, s"script.name: ${script.name}"))
    ret.contracts.foreach(contract => each(contract.warnings, s"contract.name: ${contract.name}"))
    if (warningAsError) {
      checkWaringAsError
    } else {
      0
    }
  }
}
