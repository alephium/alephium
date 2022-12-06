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

import org.alephium.util.AlephiumSpec

class CliSpec extends AlephiumSpec {

  it should "be able to compile the specified directory" in {
    val baseDir = "src/test/resources"

    def assertProject(
        sourcePath: String,
        artifactsPath: String,
        archivesPath: String,
        recursive: Boolean = true
    ) = {
      val config = Config(contracts = sourcePath, artifacts = artifactsPath)
      val args   = Array("-c", sourcePath, "-a", artifactsPath)

      assert(Cli().call(args) == 0)
      val latestArchives =
        Compiler.getSourceFiles(config.artifactsPath().toFile, ".json", recursive)
      val archivesFile = new File(archivesPath).getCanonicalFile
      val archives = Compiler
        .getSourceFiles(archivesFile, ".json", recursive)
        .map(file => {
          val path = file.toPath
          path.subpath(archivesFile.toPath.getNameCount, path.getNameCount)
        })
        .sorted

      latestArchives
        .map(file => {
          val path = file.toPath
          path.subpath(config.artifactsPath().getNameCount, path.getNameCount)
        })
        .sorted is archives

      latestArchives.foreach(file => {
        print(file.getPath)
        print("\n")
      })
      latestArchives.foreach(file => assert(Compiler.deleteFile(file)))
    }

    val project1 = baseDir + "/project1"
    assertProject(
      project1 + "/contracts1",
      project1 + "/artifacts1",
      project1 + "/artifacts"
    )

    val project2 = baseDir + "/project2"
    assertProject(
      project2,
      project2,
      project2 + "/artifacts",
      recursive = false
    )

    val project3 = baseDir + "/project3"
    assertProject(
      project3 + "/project1/contracts1",
      project3 + "/project1/artifacts1",
      project3 + "/project1/artifacts"
    )
    assertProject(
      project3 + "/project2",
      project3 + "/project2",
      project3 + "/project2/artifacts",
      recursive = false
    )
  }
}
