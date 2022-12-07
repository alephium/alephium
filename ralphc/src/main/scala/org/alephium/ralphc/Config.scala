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
import java.nio.file.Path

import org.alephium.ralph.CompilerOptions

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.ArrayEquals"
  )
)
final case class Configs(
    debug: Boolean = false,
    warningAsError: Boolean = false,
    ignoreUnusedConstantsWarnings: Boolean = false,
    ignoreUnusedVariablesWarnings: Boolean = false,
    ignoreUnusedFieldsWarnings: Boolean = false,
    ignoreUpdateFieldsCheckWarnings: Boolean = false,
    ignoreUnusedPrivateFunctionsWarnings: Boolean = false,
    ignoreExternalCallCheckWarnings: Boolean = false,
    contracts: Array[String] = Array("contracts"),
    artifacts: Array[String] = Array("artifacts")
) {
  private def compilerOptions(): CompilerOptions = {
    CompilerOptions(
      ignoreUnusedConstantsWarnings = ignoreUnusedConstantsWarnings,
      ignoreUnusedVariablesWarnings = ignoreUnusedVariablesWarnings,
      ignoreUnusedFieldsWarnings = ignoreUnusedFieldsWarnings,
      ignoreUpdateFieldsCheckWarnings = ignoreUpdateFieldsCheckWarnings,
      ignoreUnusedPrivateFunctionsWarnings = ignoreUnusedPrivateFunctionsWarnings,
      ignoreExternalCallCheckWarnings = ignoreExternalCallCheckWarnings
    )
  }

  def configs(): Array[Config] = {
    var i                    = 0
    var array: Array[Config] = Array.empty
    while (i < contracts.length && i < artifacts.length) {
      array = array :+ Config(
        options = compilerOptions(),
        contracts = contracts(i),
        artifacts = artifacts(i)
      )
      i += 1
    }
    array
  }
}

final case class Config(
    options: CompilerOptions,
    contracts: String,
    artifacts: String
) {
  def compilerOptions(): CompilerOptions = options

  def contractsPath(): Path = new File(contracts).getCanonicalFile.toPath

  def artifactsPath(): Path = new File(artifacts).getCanonicalFile.toPath
}
