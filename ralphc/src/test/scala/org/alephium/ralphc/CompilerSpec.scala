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
import java.nio.file.{Files, Paths}

import org.alephium.util.AlephiumSpec

class CompilerSpec extends AlephiumSpec {

  it should "be able to compile project" in {
    val rootPath  = Files.createTempDirectory("project")
    val sourceDir = rootPath.resolve("contracts")
    val config    = Config(contracts = sourceDir.toFile)
    val contract =
      s"""
         |Contract Foo(a: Bool, b: I256, c: U256, d: ByteVec, e: Address) {
         |  pub fn foo() -> (Bool, I256, U256, ByteVec, Address) {
         |    return a, b, c, d, e
         |  }
         |}
         |""".stripMargin

    val script =
      s"""
         |TxScript Main(x: U256, y: U256) {
         |  assert!(x != y, 0)
         |}
         |""".stripMargin

    val interface =
      s"""
         |Interface Math {
         |  pub fn math() -> U256
         |}
         |""".stripMargin

    val abstractContract =
      s"""
         |Abstract Contract Action(){
         |  pub fn action() -> ()
         |}
         |""".stripMargin
    val fooDir    = sourceDir.resolve("foo")
    val mathDir   = sourceDir.resolve("math")
    val actionDir = sourceDir.resolve("action")
    fooDir.toFile.mkdirs()
    mathDir.toFile.mkdirs()
    actionDir.toFile.mkdirs()
    val foo    = Files.createFile(fooDir.resolve("foo.ral"))
    val math   = Files.createFile(mathDir.resolve("math.ral"))
    val action = Files.createFile(actionDir.resolve("action.ral"))
    val main   = Files.createFile(sourceDir.resolve("main.ral"))
    Files.write(foo, contract.getBytes())
    Files.write(math, interface.getBytes())
    Files.write(action, abstractContract.getBytes())
    Files.write(main, script.getBytes())

    assert(Compiler.compileProject(config).isRight)
    assert(Compiler.metaInfos.contains("Foo"))
    assert(Compiler.metaInfos.contains("Math"))
    assert(Compiler.metaInfos.contains("Action"))
    assert(Compiler.metaInfos.contains("Main"))
    assert(!Compiler.metaInfos.contains("main"))
    assert(deleteFolder(rootPath.toFile))
  }

  it should "be able to compile the specified directory" in {
    val baseDir = Paths.get("src/test/resources")

    def assertProjectX(source: String, artifacts: String) = {
      val contracts = baseDir.resolve(source).toFile
      val config = Config(
        debug = true,
        contracts = contracts,
        artifacts = new File(artifacts)
      )
      assert(Compiler.compileProject(config).isRight)
      val latestArchives = Compiler
        .getSourceFiles(config.artifactsPath().toFile, ".json")
        .map(file => {
          val path = file.toPath
          path.subpath(config.artifactsPath().getNameCount, path.getNameCount)
        })
      val artifactsPath = config.projectPath().resolve("artifacts")
      val archives = Compiler
        .getSourceFiles(config.projectPath().resolve("artifacts").toFile, ".json")
        .map(file => {
          val path = file.toPath
          path.subpath(artifactsPath.getNameCount, path.getNameCount)
        })
      latestArchives is archives
      assert(deleteFolder(config.artifactsPath().toFile))
    }
    assertProjectX("project1/contracts1", "artifacts1")
    assertProjectX("project2/contracts2", "artifacts2")
    assertProjectX("project3/contracts3", "artifacts3")
  }

  it should "not compile successfully" in {
    val baseDir = Paths.get("src/test/")
    assert(Compiler.compileProject(Config(contracts = baseDir.toFile)).isLeft)
    assert(Compiler.compileProject(Config(contracts = Paths.get("").toFile)).isLeft)
  }

  def deleteFolder(file: File): Boolean = {
    for (subFile <- file.listFiles) {
      if (subFile.isDirectory) {
        deleteFolder(subFile)
      } else {
        subFile.delete
      }
    }
    file.delete()
  }
}
