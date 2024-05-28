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
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.io.Source
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.{Failure, Success, Using}

import org.alephium.api.ApiModelCodec
import org.alephium.api.UtilJson._
import org.alephium.api.model.{CompileContractResult, CompileProjectResult, CompileScriptResult}
import org.alephium.crypto
import org.alephium.json.Json._
import org.alephium.ralph
import org.alephium.util.AVector

@SuppressWarnings(Array("org.wartremover.warts.ToString"))
object Codec extends ApiModelCodec {
  implicit val pathWriter: Writer[Path] = StringWriter.comap[Path](_.toString)
  implicit val pathReader: Reader[Path] = StringReader.map(Paths.get(_))

  implicit val ralphCompilerOptionsRW: ReadWriter[ralph.CompilerOptions] = macroRW
  implicit val compileScriptResultSigRW: ReadWriter[ScriptResult]        = macroRW
  implicit val compileContractResultSigRW: ReadWriter[ContractResult]    = macroRW
  implicit val codeInfoRW: ReadWriter[CodeInfo]                          = macroRW
  implicit val artifactsRW: ReadWriter[Artifacts]                        = macroRW
  implicit val configsRW: ReadWriter[Configs]                            = macroRW
}

final case class Compiler(config: Config) {
  val metaInfos: mutable.Map[String, MetaInfo] = mutable.SeqMap.empty[String, MetaInfo]
  import Codec._

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.ToString",
      "org.wartremover.warts.TryPartial",
      "org.wartremover.warts.PlatformDefault"
    )
  )
  private def analysisCodes(): String = {
    Compiler
      .getSourceFiles(config.contractPath, ".ral")
      .map(sourcePath => {
        val sourceCode = Using(Source.fromFile(sourcePath.toFile)) { _.mkString } match {
          case Success(value) =>
            value.replace("\r\n", "\n") // to make sure same source hash for different OS
          case Failure(exception) => throw exception
        }
        val sourceCodeHash = crypto.Sha256.hash(sourceCode).toHexString
        TypedMatcher
          .matcher(sourceCode)
          .foreach(name => {
            val artifactPath = Paths.get(
              config.artifactPath
                .resolve(
                  sourcePath.subpath(config.contractPath.getNameCount, sourcePath.getNameCount)
                )
                .toString
                + ".json"
            )
            val meta = MetaInfo(
              name,
              artifactPath,
              CodeInfo(
                config.contractPath.relativize(sourcePath).toString,
                sourceCodeHash,
                CompileProjectResult.Patch(""),
                "",
                AVector()
              )
            )
            metaInfos.addOne((name, meta))
          })
        sourceCode
      })
      .mkString
  }

  def compileProject(): Either[String, CompileProjectResult] = {
    val codes = analysisCodes()
    if (codes.isEmpty) {
      Left(s"There are no contracts in the folder: <${config.contractPath}>")
    } else {
      ralph.Compiler
        .compileProject(codes, config.compilerOptions)
        .map(p => {
          p._1.foreach(cc => {
            val c     = CompileContractResult.from(cc)
            val value = metaInfos(c.name)
            value.codeInfo.warnings = c.warnings
            value.codeInfo.bytecodeDebugPatch = c.bytecodeDebugPatch
            value.codeInfo.codeHashDebug = c.codeHashDebug.toHexString
            metaInfos.update(c.name, value)
            Compiler.writer(ContractResult.from(c), value.artifactPath)
          })
          p._2.foreach(ss => {
            val s     = CompileScriptResult.from(ss)
            val value = metaInfos(s.name)
            value.codeInfo.warnings = s.warnings
            value.codeInfo.bytecodeDebugPatch = s.bytecodeDebugPatch
            metaInfos.update(s.name, value)
            Compiler.writer(ScriptResult.from(s), value.artifactPath)
          })
          // TODO: handle struct definitions
          Compiler.writer(
            Artifacts(
              config.compilerOptions,
              mutable.SeqMap(
                metaInfos
                  .map(item => (item._2.name, item._2.codeInfo))
                  .toSeq
                  .sortWith(_._1 < _._1)*
              )
            ),
            config.artifactPath.resolve(".project.json")
          )
          CompileProjectResult.from(p._1, p._2, p._3)
        })
        .left
        .map(_.toString)
    }
  }
}

object Compiler {
  def writer[T: Writer](s: T, path: Path): Unit = {
    path.getParent.toFile.mkdirs()
    val writer = new PrintWriter(path.toFile)
    writer.write(write(s, 2))
    writer.close()
  }

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.ToString",
      "org.wartremover.warts.DefaultArguments",
      "org.wartremover.warts.Recursion"
    )
  )
  def getSourceFiles(path: Path, ext: String, recursive: Boolean = true): Seq[Path] = {
    if (Files.isDirectory(path)) {
      val (allFiles, allDirs) =
        Files.list(path).iterator().asScala.toSeq.partition(p => !Files.isDirectory(p))
      val expectedFiles = allFiles.filter(p => p.toString().endsWith(ext))
      if (recursive) {
        expectedFiles ++ allDirs.flatMap(getSourceFiles(_, ext))
      } else {
        expectedFiles
      }
    } else {
      if (Files.isRegularFile(path) && path.endsWith(ext)) {
        Seq(path)
      } else {
        Seq.empty
      }
    }
  }

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Recursion"
    )
  )
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
