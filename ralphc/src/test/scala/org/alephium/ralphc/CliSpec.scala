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

import org.alephium.util.AlephiumSpec

class CliSpec extends AlephiumSpec {
  val baseDir     = "src/test/resources"
  val project1    = baseDir + "/project1"
  val contracts1  = project1 + "/contracts1"
  val artifacts1  = project1 + "/artifacts1"
  val project2    = baseDir + "/project2"
  val project3    = baseDir + "/project3"
  val project31   = project3 + "/project1"
  val contracts31 = project31 + "/contracts1"
  val artifacts31 = project31 + "/artifacts1"
  val project32   = project3 + "/project2"

  def assertProject(
      sourcePath: String,
      artifactsPath: String,
      recursive: Boolean = true
  ) = {
    val cli = Cli()
    assert(cli.call(Array("-c", sourcePath, "-a", artifactsPath)) == 0)
    cli.configs
      .configs()
      .foreach(config => {
        val latestArchives =
          Compiler.getSourceFiles(config.artifactsPath().toFile, ".json", recursive)
        var archivesPath = config.contractsPath().getParent.resolve("artifacts")
        if (!recursive) {
          archivesPath = config.contractsPath().resolve("artifacts")
        }
        val archivesFile = archivesPath.toFile
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
        latestArchives.foreach(file => assert(Compiler.deleteFile(file)))
      })
  }

  it should "be able to compile project" in {
    assertProject(contracts1, artifacts1)
    assertProject(project2, project2, recursive = false)
    assertProject(contracts31, artifacts31)
    assertProject(project32, project32, recursive = false)
  }

  it should "be able to compile multi-project contracts" in {
    assertProject(contracts1 + "," + contracts31, artifacts1 + "," + artifacts31)
    assertProject(project2 + "," + project32, project2 + "," + project32, recursive = false)
  }

  it should "not to compile contracts" in {
    assert(Cli().call(Array("-c", contracts1 + "," + contracts31, "-a", artifacts1)) != 0)
  }
}
