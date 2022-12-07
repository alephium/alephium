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
import scala.util.Using

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
    "org.wartremover.warts.Recursion",
    "org.wartremover.warts.JavaSerializable",
    "org.wartremover.warts.PlatformDefault"
  )
)
object Codec {
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
  implicit val configsRW: ReadWriter[Configs]                                 = macroRW
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.PublicInference",
    "org.wartremover.warts.Recursion",
    "org.wartremover.warts.PlatformDefault",
    "org.wartremover.warts.DefaultArguments"
  )
)
final case class Compiler(config: Config) {
  val metaInfos: mutable.Map[String, MetaInfo] = mutable.SeqMap.empty[String, MetaInfo]
  import Codec.*

  private def analysisCodes(): String = {
    Compiler
      .getSourceFiles(config.contractsPath().toFile, ".ral")
      .map(file => {
        val sourceCode     = Using(Source.fromFile(file)) { _.mkString }.getOrElse("")
        val sourceCodeHash = crypto.Sha256.hash(sourceCode).toHexString
        TypedMatcher
          .matcher(sourceCode)
          .foreach(name => {
            val path = file.getCanonicalFile.toPath
            val sourcePath =
              path.subpath(config.contractsPath().getParent.getNameCount, path.getNameCount)
            val savePath = Paths.get(
              config
                .artifactsPath()
                .resolve(path.subpath(config.contractsPath().getNameCount, path.getNameCount))
                .toFile
                .getPath + ".json"
            )
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

  def compileProject(): Either[String, CompileProjectResult] = {
    ralph.Compiler
      .compileProject(analysisCodes(), config.compilerOptions())
      .map(p => {
        p._1.foreach(c => saveContract(CompileContractResult.from(c)))
        p._2.foreach(s => saveScript(CompileScriptResult.from(s)))
        saveProjectArtifacts()
        CompileProjectResult.from(p._1, p._2)
      })
      .left
      .map(_.toString)
  }

  def saveContract(c: CompileContractResult): Unit = {
    val value = metaInfos(c.name)
    value.codeInfo.warnings = c.warnings
    value.codeInfo.bytecodeDebugPatch = c.bytecodeDebugPatch
    value.codeInfo.codeHashDebug = c.codeHashDebug
    metaInfos.addOne((c.name, value))
    Compiler.writer(ContractResult.from(c), value.ArtifactPath)
  }

  def saveScript(s: CompileScriptResult): Unit = {
    val value = metaInfos(s.name)
    value.codeInfo.warnings = s.warnings
    value.codeInfo.bytecodeDebugPatch = s.bytecodeDebugPatch
    metaInfos.addOne((s.name, value))
    Compiler.writer(ScriptResult.from(s), value.ArtifactPath)
  }

  def saveProjectArtifacts(): Unit = {
    Compiler.writer(
      Artifacts(
        config.compilerOptions(),
        metaInfos.map(item => (item._2.sourcePath.toFile.getPath, item._2.codeInfo)).toMap
      ),
      config.artifactsPath().resolve(".project.json")
    )
  }
}

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Recursion",
    "org.wartremover.warts.DefaultArguments"
  )
)
object Compiler {
  def writer[T: Writer](s: T, path: Path): Unit = {
    path.getParent.toFile.mkdirs()
    val writer = new PrintWriter(path.toFile)
    writer.write(write(s, 2))
    writer.close()
  }
  def getSourceFiles(file: File, ext: String, recursive: Boolean = true): Array[File] = {
    if (file.isDirectory) {
      val (fullFiles, fullDirs) = file.listFiles().partition(!_.isDirectory)
      val files                 = fullFiles.filter(t => t.getPath.endsWith(ext))
      if (recursive) {
        files ++ fullDirs.flatMap(getSourceFiles(_, ext))
      } else {
        files
      }
    } else {
      if (file.isFile && file.getPath.endsWith(ext)) {
        Array(file)
      } else {
        Array.empty
      }
    }
  }

  def deleteFile(file: File): Boolean = {
    if (file.isDirectory) {
      var result = false
      for (subFile <- file.listFiles) {
        result = deleteFile(subFile)
      }
      result
    } else {
      file.delete()
    }
  }
}
