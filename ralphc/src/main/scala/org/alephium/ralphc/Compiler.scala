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

import java.io.{File, PrintWriter}
import java.nio.file.{Path, Paths}

import scala.collection.mutable
import scala.io.Source

import org.alephium.api.{failed, Try}
import org.alephium.api.UtilJson.*
import org.alephium.api.model.{
  CompileContractResult,
  CompileProjectResult,
  CompileResult,
  CompileScriptResult
}
import org.alephium.crypto
import org.alephium.json.Json.*
import org.alephium.protocol.Hash
import org.alephium.ralph
import org.alephium.ralph.CompilerOptions
import org.alephium.util.AVector

@SuppressWarnings(
  Array(
    "org.wartremover.warts.ToString",
    "org.wartremover.warts.OptionPartial",
    "org.wartremover.warts.PublicInference",
    "org.wartremover.warts.Recursion",
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.PlatformDefault",
    "org.wartremover.warts.DefaultArguments"
  )
)
object Compiler {
  var compilerOptions: CompilerOptions  = CompilerOptions.Default
  val metaInfos                         = mutable.Map.empty[String, MetaInfo]
  var projectDir                        = "contracts"
  var projectDirName                    = "contracts"
  var artifactsName                     = "artifacts"
  implicit val hashWriter: Writer[Hash] = StringWriter.comap[Hash](_.toHexString)
  implicit val hashReader: Reader[Hash] = byteStringReader.map(Hash.from(_).get)
  implicit val compilerOptionsRW: ReadWriter[CompilerOptions] = macroRW
  implicit val compilePatchRW: ReadWriter[CompileProjectResult.Patch] =
    readwriter[String].bimap(_.value, CompileProjectResult.Patch)
  implicit val compileResultFieldsRW: ReadWriter[CompileResult.FieldsSig]     = macroRW
  implicit val compileResultFunctionRW: ReadWriter[CompileResult.FunctionSig] = macroRW
  implicit val compileResultEventRW: ReadWriter[CompileResult.EventSig]       = macroRW
  implicit val compileScriptResultRW: ReadWriter[CompileScriptResult]         = macroRW
  implicit val compileContractResultRW: ReadWriter[CompileContractResult]     = macroRW
  implicit val compileProjectResultRW: ReadWriter[CompileProjectResult]       = macroRW
  implicit val compileScriptResultSigRW: ReadWriter[ScriptResult]             = macroRW
  implicit val compileContractResultSigRW: ReadWriter[ContractResult]         = macroRW
  implicit val codeInfoRW: ReadWriter[CodeInfo]                               = macroRW
  implicit val artifactsRW: ReadWriter[Artifacts]                             = macroRW

  def getFile(file: File): Array[File] = {
    val files = file
      .listFiles()
      .filter(!_.isDirectory)
      .filter(t => t.toString.endsWith(".ral"))
    files ++ file.listFiles().filter(_.isDirectory).flatMap(getFile)
  }

  def projectCodes(rootPath: String): String = {
    getFile(new File(rootPath))
      .map(file => {
        val sourceCode     = Source.fromFile(file).mkString
        val sourceCodeHash = crypto.Sha256.hash(sourceCode).toHexString
        TypedMatcher
          .matcher(sourceCode)
          .map(_.getName)
          .map(name => {
            val path       = file.toPath.toString
            val sourcePath = Paths.get(path.substring(path.indexOf(this.projectDirName)))
            val savePath   = Paths.get(path.replace(projectDirName, artifactsName) + ".json")
            val meta = MetaInfo(
              name,
              sourcePath,
              savePath,
              CodeInfo(sourceCodeHash, CompileProjectResult.Patch(""), Hash.zero, AVector())
            )
            metaInfos.addOne((name, meta))
          })
        sourceCode
      })
      .mkString
  }

  def compileProject(
      rootPath: String,
      artifactsName: String,
      compilerOptions: CompilerOptions = CompilerOptions.Default
  ): Try[CompileProjectResult] = {
    this.projectDir = rootPath
    this.artifactsName = artifactsName
    this.projectDirName = new File(rootPath).getName
    this.compilerOptions = compilerOptions
    ralph.Compiler
      .compileProject(projectCodes(rootPath), compilerOptions = compilerOptions)
      .map(p => {
        p._1.foreach(c => saveContract(CompileContractResult.from(c)))
        p._2.foreach(s => saveScript(CompileScriptResult.from(s)))
        saveProjectArtifacts()
        CompileProjectResult.from(p._1, p._2)
      })
      .left
      .map(error => failed(error.toString))
  }

  def saveContract(c: CompileContractResult): Unit = {
    val contract = ContractResult(
      c.version,
      c.name,
      c.bytecode,
      c.codeHash,
      c.fields,
      c.events,
      c.functions
    )
    val value = metaInfos(c.name)
    value.codeInfo.warnings = c.warnings
    value.codeInfo.bytecodeDebugPatch = c.bytecodeDebugPatch
    value.codeInfo.codeHashDebug = c.codeHashDebug
    metaInfos.addOne((c.name, value))

    val code = write(contract, 2)
    saveResult(code, metaInfos(c.name).ArtifactPath)
  }

  def saveScript(s: CompileScriptResult): Unit = {
    val script = ScriptResult(
      s.version,
      s.name,
      s.bytecodeTemplate,
      s.fields,
      s.functions
    )
    val value = metaInfos(s.name)
    value.codeInfo.warnings = s.warnings
    value.codeInfo.bytecodeDebugPatch = s.bytecodeDebugPatch
    metaInfos.addOne((s.name, value))

    val code = write(script, 2)
    saveResult(code, metaInfos(s.name).ArtifactPath)
  }

  def saveProjectArtifacts(): Unit = {
    val codes = write(
      Artifacts(
        compilerOptions,
        metaInfos.map(item => (item._2.sourcePath.toString, item._2.codeInfo)).toMap
      ),
      2
    )
    saveResult(codes, Paths.get(artifactsPath().toString, ".project.json"))
  }

  def saveResult(code: String, path: Path): Unit = {
    path.getParent.toFile.mkdirs()
    val writer = new PrintWriter(path.toFile)
    writer.write(code)
    writer.close()
  }

  def contractPath(): Path = Paths.get(projectDir)

  def rootPath(): Path = contractPath().getParent

  def artifactsPath(): Path = rootPath().resolve(artifactsName)

}
