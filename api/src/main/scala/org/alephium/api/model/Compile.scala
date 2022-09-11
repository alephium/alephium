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

package org.alephium.api.model

import org.alephium.protocol.vm.lang

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object Compile {
  trait Common {
    def code: String
    def compilerOptions: Option[CompilerOptions]

    def getLangCompilerOptions(): lang.CompilerOptions = {
      compilerOptions match {
        case None          => lang.CompilerOptions.Default
        case Some(options) => options.toLangCompilerOptions()
      }
    }
  }

  // use different type to avoid ambiguous implicit values in endpoint examples
  final case class Script(code: String, compilerOptions: Option[CompilerOptions] = None)
      extends Common
  final case class Contract(code: String, compilerOptions: Option[CompilerOptions] = None)
      extends Common
  final case class Project(code: String, compilerOptions: Option[CompilerOptions] = None)
      extends Common
}

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class CompilerOptions(
    ignoreUnusedConstantsWarnings: Option[Boolean] = None,
    ignoreUnusedVariablesWarnings: Option[Boolean] = None,
    ignoreUnusedFieldsWarnings: Option[Boolean] = None,
    ignoreUnusedPrivateFunctionsWarnings: Option[Boolean] = None,
    ignoreReadonlyCheckWarnings: Option[Boolean] = None,
    ignoreExternalCallCheckWarnings: Option[Boolean] = None
) {
  def toLangCompilerOptions(): lang.CompilerOptions = {
    lang.CompilerOptions(
      ignoreUnusedConstantsWarnings = ignoreUnusedConstantsWarnings.getOrElse(
        lang.CompilerOptions.Default.ignoreUnusedConstantsWarnings
      ),
      ignoreUnusedVariablesWarnings = ignoreUnusedVariablesWarnings.getOrElse(
        lang.CompilerOptions.Default.ignoreUnusedVariablesWarnings
      ),
      ignoreUnusedFieldsWarnings = ignoreUnusedFieldsWarnings.getOrElse(
        lang.CompilerOptions.Default.ignoreUnusedFieldsWarnings
      ),
      ignoreUnusedPrivateFunctionsWarnings = ignoreUnusedPrivateFunctionsWarnings.getOrElse(
        lang.CompilerOptions.Default.ignoreUnusedPrivateFunctionsWarnings
      ),
      ignoreReadonlyCheckWarnings = ignoreReadonlyCheckWarnings.getOrElse(
        lang.CompilerOptions.Default.ignoreReadonlyCheckWarnings
      ),
      ignoreExternalCallCheckWarnings = ignoreExternalCallCheckWarnings.getOrElse(
        lang.CompilerOptions.Default.ignoreExternalCallCheckWarnings
      )
    )
  }
}
