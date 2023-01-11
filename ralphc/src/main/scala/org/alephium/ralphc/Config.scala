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

import java.nio.file.{Path, Paths}

import scala.collection.immutable.ArraySeq

import org.alephium.ralph.CompilerOptions

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class Configs(
    debug: Boolean = false,
    warningAsError: Boolean = false,
    ignoreUnusedConstantsWarnings: Boolean = false,
    ignoreUnusedVariablesWarnings: Boolean = false,
    ignoreUnusedFieldsWarnings: Boolean = false,
    ignoreUpdateFieldsCheckWarnings: Boolean = false,
    ignoreUnusedPrivateFunctionsWarnings: Boolean = false,
    ignoreCheckExternalCallerWarnings: Boolean = false,
    contracts: ArraySeq[Path] = ArraySeq(Paths.get(".")),
    artifacts: ArraySeq[Path] = ArraySeq(Paths.get("."))
) {
  private def compilerOptions(): CompilerOptions = {
    CompilerOptions(
      ignoreUnusedConstantsWarnings = ignoreUnusedConstantsWarnings,
      ignoreUnusedVariablesWarnings = ignoreUnusedVariablesWarnings,
      ignoreUnusedFieldsWarnings = ignoreUnusedFieldsWarnings,
      ignoreUpdateFieldsCheckWarnings = ignoreUpdateFieldsCheckWarnings,
      ignoreUnusedPrivateFunctionsWarnings = ignoreUnusedPrivateFunctionsWarnings,
      ignoreCheckExternalCallerWarnings = ignoreCheckExternalCallerWarnings
    )
  }

  def configs(): Array[Config] = {
    contracts
      .zip(artifacts)
      .map(value =>
        Config(
          compilerOptions = compilerOptions(),
          contractPath = value._1,
          artifactPath = value._2
        )
      )
      .toArray
  }
}

final case class Config(
    compilerOptions: CompilerOptions,
    contractPath: Path,
    artifactPath: Path
)
