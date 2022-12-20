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

import java.nio.file.{Files, Paths}

import scala.io.Source

import org.alephium.util.AlephiumSpec

class CliSpec extends AlephiumSpec {
  val baseDir     = "src/test/resources"
  val project1    = baseDir + "/project1"
  val project2    = baseDir + "/project2"
  val project3    = baseDir + "/project3"
  val project31   = project3 + "/project1"
  val project32   = project3 + "/project2"
  val contracts1  = project1 + "/contracts"
  val contracts31 = project31 + "/contracts"

  def assertProject(
      sourcePath: String,
      artifactsPath: String,
      isRecode: Boolean = false
  ) = {
    val cli = Cli()
    assert(cli.call(Array("-c", sourcePath, "-a", artifactsPath)) == 0)

    cli.configs
      .configs()
      .foreach { config =>
        val generatedArtifacts =
          Compiler.getSourceFiles(config.artifactPath, ".json").sorted

        val expectedArtifactsPath = if (isRecode) {
          config.contractPath.resolve("artifacts")
        } else {
          config.contractPath.getParent.resolve("artifacts")
        }
        config.artifactPath isnot expectedArtifactsPath

        val expectedArtifacts = Compiler
          .getSourceFiles(expectedArtifactsPath, ".json")
          .sorted

        generatedArtifacts.length is expectedArtifacts.length
        generatedArtifacts.zip(expectedArtifacts).foreach { case (generatedPath, expectedPath) =>
          val generatedSource = Source.fromFile(generatedPath.toFile)
          val generated =
            try generatedSource.mkString
            finally generatedSource.close()
          val expectedSource = Source.fromFile(expectedPath.toFile)
          val expected =
            try expectedSource.mkString
            finally expectedSource.close()
          generated is expected
        }
      }
  }

  it should "be able to compile project" in {
    val baseArtifacts = Files.createTempDirectory("projects").toFile.getPath
    val artifacts1    = baseArtifacts + "/artifacts1"
    val artifacts2    = baseArtifacts + "/artifacts2"
    val artifacts31   = baseArtifacts + "/artifacts31"
    val artifacts32   = baseArtifacts + "/artifacts32"
    Paths.get(artifacts1).toFile.mkdirs()
    Paths.get(artifacts2).toFile.mkdirs()
    Paths.get(artifacts31).toFile.mkdirs()
    Paths.get(artifacts32).toFile.mkdirs()
    assertProject(contracts1, artifacts1)
    assertProject(contracts31, artifacts31)
    assertProject(project2, artifacts2, isRecode = true)
    assertProject(project32, artifacts32, isRecode = true)
    assert(Compiler.deleteFile(Paths.get(baseArtifacts).toFile))
  }

  def assertProjectWithArgs(
      args: Array[String],
      expected: Int = 0,
      isRecode: Boolean = false
  ): Unit = {
    val cli = Cli()
    assert(cli.call(args) == expected)
    cli.configs
      .configs()
      .foreach { config =>
        val generatedArtifacts =
          Compiler.getSourceFiles(config.artifactPath, ".json").sorted
        val expectedArtifactsPath = if (isRecode) {
          config.contractPath.resolve("artifacts")
        } else {
          config.contractPath.getParent.resolve("artifacts")
        }
        config.artifactPath isnot expectedArtifactsPath
        val expectedArtifacts = Compiler
          .getSourceFiles(expectedArtifactsPath, ".json")
          .sorted
        generatedArtifacts.length is expectedArtifacts.length
      }
  }

  it should "be able to compile project with compile option" in {
    val baseArtifacts = Files.createTempDirectory("projects").toFile.getPath
    val artifacts1    = baseArtifacts + "/artifacts1"
    val artifacts2    = baseArtifacts + "/artifacts2"
    Paths.get(artifacts1).toFile.mkdirs()
    Paths.get(artifacts2).toFile.mkdirs()
    assertProjectWithArgs(
      Array(
        "-d",
        "--ic",
        "--iv",
        "--if",
        "--ip",
        "--ir",
        "--ie",
        "-c",
        contracts1,
        "-a",
        artifacts1
      )
    )
    assertProjectWithArgs(
      Array(
        "-d",
        "-w",
        "-c",
        contracts1,
        "-a",
        artifacts1
      ),
      -1
    )
    assertProjectWithArgs(
      Array(
        "-d",
        "-w",
        "-c",
        project2,
        "-a",
        artifacts2
      ),
      -1,
      isRecode = true
    )
    assert(Compiler.deleteFile(Paths.get(baseArtifacts).toFile))
  }

  it should "be able to compile multi-project contracts" in {
    val baseArtifacts = Files.createTempDirectory("projects").toFile.getPath
    val artifacts1    = baseArtifacts + "/artifacts1"
    val artifacts2    = baseArtifacts + "/artifacts2"
    val artifacts31   = baseArtifacts + "/artifacts31"
    val artifacts32   = baseArtifacts + "/artifacts32"
    Paths.get(artifacts1).toFile.mkdirs()
    Paths.get(artifacts2).toFile.mkdirs()
    Paths.get(artifacts31).toFile.mkdirs()
    Paths.get(artifacts32).toFile.mkdirs()
    assertProject(contracts1 + "," + contracts31, artifacts1 + "," + artifacts31)
    assertProject(project2 + "," + project32, artifacts2 + "," + artifacts32, isRecode = true)
    assert(Compiler.deleteFile(Paths.get(baseArtifacts).toFile))
  }

  it should "not to compile contracts" in {
    assert(Cli().call(Array("-c", contracts1 + "," + contracts31, "-a", "")) != 0)
    assert(Cli().call(Array("-c", "", "-a", "artifacts1" + "," + "artifacts31")) != 0)
    assert(
      Cli().call(
        Array("-c", contracts1 + "," + contracts1, "-a", "artifacts1" + "," + "artifacts31")
      ) != 0
    )
    assert(
      Cli().call(
        Array("-c", contracts1 + "," + contracts31, "-a", "artifacts1" + "," + "artifacts1")
      ) != 0
    )
  }
}
