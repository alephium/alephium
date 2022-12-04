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
import java.nio.file.Files

import org.alephium.util.AlephiumSpec

class CompilerSpec extends AlephiumSpec {
  val rootPath = Files.createTempDirectory("project")
  val config   = Config(contractsPath = rootPath.resolve("contracts").toString)
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

  it should "be able to compiler project" in {
    val sourceDir = rootPath.resolve(config.contractsDirName())
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
