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

import java.nio.file.Files

import org.alephium.ralph.CompilerOptions
import org.alephium.util.AlephiumSpec

class CompilerSpec extends AlephiumSpec {

  it should "be able to call compileProject" in {
    val rootPath  = Files.createTempDirectory("project")
    val sourceDir = rootPath.resolve("contracts")
    val config = Config(
      options = CompilerOptions.Default,
      contractPath = sourceDir,
      artifactPath = rootPath.resolve("artifacts")
    )
    val compiler = Compiler(config)
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

    assert(compiler.compileProject().isRight)
    assert(compiler.metaInfos.contains("Foo"))
    assert(compiler.metaInfos.contains("Math"))
    assert(compiler.metaInfos.contains("Action"))
    assert(compiler.metaInfos.contains("Main"))
    assert(!compiler.metaInfos.contains("main"))
    assert(Compiler.deleteFile(rootPath.toFile))
  }
}
