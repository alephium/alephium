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

package org.alephium.tools

import java.nio.file.{Files, Paths}

import scala.reflect.runtime.universe._
import scala.util.Using

import org.alephium.protocol.vm._

object GenRustDecoder {
  final case class InstrInfo(name: String, code: Int, params: Seq[String]) {
    def genEnum: String = {
      if (params.isEmpty) name else s"$name(${params.mkString(", ")})"
    }

    def genFromType: String = {
      if (params.isEmpty) {
        s"$code => Some(Self::$name)"
      } else {
        val default = params.map { tpe =>
          val baseType = if (tpe.contains("<")) tpe.slice(0, tpe.indexOf('<')) else tpe
          s"$baseType::default()"
        }
        s"$code => Some(Self::$name(${default.mkString(", ")}))"
      }
    }

    def genStepSize: String = {
      if (params.isEmpty) {
        ""
      } else {
        val args         = params.indices.map(idx => s"v$idx")
        val stepSizeCall = args.map(v => s"$v.step_size()")
        s"Self::$name(${args.mkString(", ")}) => ${stepSizeCall.mkString(" + ")}"
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genDecode: String = {
      if (params.isEmpty) {
        ""
      } else {
        val args = params.indices.map(idx => s"v$idx")
        val decodeBody = if (args.length == 1) {
          s"v0.decode(buffer, stage)"
        } else {
          val branches = args.init
            .map(v => s"if stage.step < $v.step_size() { $v.decode(buffer, stage) }")
            .mkString(" else ")
          s"$branches else { ${args.last}.decode(buffer, stage) }"
        }
        s"Self::$name(${args.mkString(", ")}) => $decodeBody"
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def getRustType(tpe: String): String = {
    tpe match {
      case "Byte"               => "Byte"
      case "Int"                => "I32"
      case "U256"               => "U256"
      case "I256"               => "I256"
      case "Address"            => "LockupScript"
      case "ByteVec"            => "ByteString"
      case "Selector"           => "MethodSelector"
      case s"AVector[${value}]" => s"AVector<${getRustType(value)}>"
      case t                    => throw new RuntimeException(s"unknown type $t")
    }
  }

  private def getParams(tpe: Symbol): Seq[String] = {
    if (tpe.isModuleClass) {
      Seq.empty
    } else {
      val params =
        tpe.asClass.primaryConstructor.asMethod.paramLists.headOption.getOrElse(Seq.empty)
      params.map(p => getRustType(GenInstrCodec.getScalaType(p.typeSignature)))
    }
  }

  // scalastyle:off method.length magic.number
  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def genCode(allInstrs: Set[Symbol], instrCodes: Map[String, Int]): String = {
    val instrInfos = allInstrs.toSeq
      .map { instr =>
        val name = instr.name.toString
        val code = instrCodes.getOrElse(
          name,
          throw new RuntimeException(s"failed to get code for instr $name")
        )
        val params = getParams(instr)
        InstrInfo(name, code, params)
      }
      .sortBy(_.code)

    s"""
       |// auto-generated, do not edit
       |use super::*;
       |use crate::buffer::{Buffer, Writable};
       |use crate::decode::*;
       |use crate::types::method_selector::MethodSelector;
       |
       |#[cfg_attr(test, derive(Debug, PartialEq))]
       |pub enum Instr {
       |    ${instrInfos.map(_.genEnum).mkString(",\n    ")},
       |    Unknown,
       |}
       |impl Reset for Instr {
       |    fn reset(&mut self) {
       |        *self = Self::Unknown;
       |    }
       |}
       |impl Default for Instr {
       |    fn default() -> Self {
       |        Self::Unknown
       |    }
       |}
       |impl Instr {
       |    fn from_type(tpe: u8) -> Option<Self> {
       |        match tpe {
       |${instrInfos.map(i => GenInstrCodec.padLine(i.genFromType, 12)).mkString(",\n")},
       |            _ => None,
       |        }
       |    }
       |}
       |impl RawDecoder for Instr {
       |    fn step_size(&self) -> u16 {
       |        match self {
       |${instrInfos.view
        .filter(_.params.nonEmpty)
        .map(i => GenInstrCodec.padLine(i.genStepSize, 12))
        .mkString(",\n")},
       |            _ => 1,
       |        }
       |    }
       |    fn decode<W: Writable>(
       |        &mut self,
       |        buffer: &mut Buffer<'_, W>,
       |        stage: &DecodeStage,
       |    ) -> DecodeResult<DecodeStage> {
       |        if buffer.is_empty() {
       |            return Ok(DecodeStage { ..*stage });
       |        }
       |        if let Self::Unknown = self {
       |            let tpe = buffer.consume_byte().unwrap();
       |            let result = Self::from_type(tpe);
       |            if let Some(instr) = result {
       |                *self = instr;
       |            } else {
       |                *self = Instr::Unknown;
       |            }
       |        };
       |        match self {
       |${instrInfos.view
        .filter(_.params.nonEmpty)
        .map(i => GenInstrCodec.padLine(i.genDecode, 12))
        .mkString(",\n")},
       |            Self::Unknown => Ok(DecodeStage::COMPLETE), // skip unknown instr
       |            _ => Ok(DecodeStage::COMPLETE),
       |        }
       |    }
       |}
       |""".stripMargin
  }
  // scalastyle:on method.length
}

object GenTsCodec {
  final case class InstrParam(name: String, tpe: String, codec: String) {
    lazy val signature = s"$name: $tpe"
  }

  final case class InstrInfo(name: String, code: Int, params: Seq[InstrParam]) {
    lazy val tsParams = params.map(_.signature).mkString(", ")
    lazy val hexCode  = String.format("0x%02x", code)

    def genCode: String = s"export const ${name}Code = $hexCode"
    def genEnum: String = s"{ name: '$name'; code: $hexCode, $tsParams }"

    def genConstructor: String = {
      if (params.isEmpty) {
        s"""export const $name: Instr = { name: '$name', code: $hexCode }"""
      } else {
        s"""
           |export const $name: ($tsParams) => Instr = ($tsParams) => {
           |  return { name: '$name', code: $hexCode, ${params.map(_.name).mkString(", ")} }
           |}
           |""".stripMargin
      }
    }

    def genEncode: String = {
      if (params.isEmpty) {
        s"""
           |case '$name':
           |  return new Uint8Array([$hexCode])
           |""".stripMargin
      } else {
        val encodeParams = params.map(p => s"...${p.codec}.encode(instr.${p.name})").mkString(", ")
        s"""
           |case '$name':
           |  return new Uint8Array([$hexCode, $encodeParams])
           |""".stripMargin
      }
    }

    def genDecode: String = {
      if (params.isEmpty) {
        s"""
           |case $hexCode:
           |  return $name
           |""".stripMargin
      } else {
        val decodeParams = params.map(p => s"${p.codec}._decode(input)").mkString(", ")
        s"""
           |case $hexCode:
           |  return $name($decodeParams)
           |""".stripMargin
      }
    }
  }

  object InstrInfo {
    def from(name: String, code: Int, params: Seq[InstrParam]): InstrInfo = {
      val newParams = params.map { p =>
        if (p.name == "const") InstrParam("value", p.tpe, p.codec) else p
      }
      InstrInfo(name, code, newParams)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def getTsTypeAndCodec(tpe: String): (String, String) = {
    tpe match {
      case "Byte"             => ("number", "byteCodec")
      case "Int"              => ("number", "i32Codec")
      case "U256"             => ("bigint", "u256Codec")
      case "I256"             => ("bigint", "i256Codec")
      case "Address"          => ("LockupScript", "lockupScriptCodec")
      case "ByteVec"          => ("ByteString", "byteStringCodec")
      case "Selector"         => ("number", "intAs4BytesCodec")
      case "AVector[ByteVec]" => ("ByteString[]", "byteStringsCodec")
      case t                  => throw new RuntimeException(s"unknown type $t")
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def getParams(tpe: Symbol): Seq[InstrParam] = {
    if (tpe.isModuleClass) {
      Seq.empty
    } else {
      val params =
        tpe.asClass.primaryConstructor.asMethod.paramLists.headOption.getOrElse(Seq.empty)
      params.map { p =>
        val (tpe, codec) = getTsTypeAndCodec(GenInstrCodec.getScalaType(p.typeSignature))
        InstrParam(p.name.toString, tpe, codec)
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  def genCode(allInstrs: Set[Symbol], instrCodes: Map[String, Int]): String = {
    val instrInfos = allInstrs.toSeq
      .map { instr =>
        val name = instr.name.toString
        val code = instrCodes.getOrElse(
          name,
          throw new RuntimeException(s"failed to get code for instr $name")
        )
        val params = getParams(instr)
        InstrInfo.from(name, code, params)
      }
      .sortBy(_.code)

    val enums        = instrInfos.map(_.genEnum).mkString(" | \n")
    val constructors = instrInfos.map(_.genConstructor).mkString("\n")
    s"""
       |// auto-generated, do not edit
       |import { ArrayCodec } from './array-codec'
       |import { i256Codec, u256Codec, i32Codec } from './compact-int-codec'
       |import { ByteString, byteStringCodec, byteStringsCodec } from './bytestring-codec'
       |import { LockupScript, lockupScriptCodec } from './lockup-script-codec'
       |import { byteCodec, Codec } from './codec'
       |import { intAs4BytesCodec } from './int-as-4bytes-codec'
       |import { Reader } from './reader'
       |
       |export type Instr = $enums
       |
       |${instrInfos.view.filter(_.params.nonEmpty).map(_.genCode).mkString("\n")}
       |
       |$constructors
       |
       |export class InstrCodec extends Codec<Instr> {
       |  encode(instr: Instr): Uint8Array {
       |    switch (instr.name) {
       |      ${instrInfos.map(i => GenInstrCodec.padLine(i.genEncode, 6)).mkString("")}
       |    }
       |  }
       |  _decode(input: Reader): Instr {
       |    const code = input.consumeByte()
       |    switch (code) {
       |      ${instrInfos.map(i => GenInstrCodec.padLine(i.genDecode, 6)).mkString("")}
       |      default:
       |        throw new Error(`Unknown instr code: $${code}`)
       |    }
       |  }
       |}
       |
       |export const instrCodec = new InstrCodec()
       |export const instrsCodec = new ArrayCodec<Instr>(instrCodec)
       |""".stripMargin
  }
}

object GenInstrCodec extends App {
  def padLine(str: String, spaceSize: Int): String = {
    val lines = str.split("\n")
    lines.view.map(l => s"${" " * spaceSize}$l").mkString("\n")
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion", "org.wartremover.warts.ToString"))
  def getScalaType(tpe: Type): String = {
    tpe match {
      case TypeRef(_, sym, args) if args.nonEmpty =>
        val typeName       = sym.name.toString
        val simplifiedArgs = args.map(getScalaType).mkString(", ")
        s"$typeName[$simplifiedArgs]"
      case _ =>
        tpe.typeSymbol.name.toString
    }
  }

  private def writeToFile(path: String, content: String): Unit = {
    Using(Files.newBufferedWriter(Paths.get(path))) { writer =>
      writer.write(content)
    } match {
      case scala.util.Success(_) => print(s"Successfully saved to $path\n")
      case scala.util.Failure(e) => e.printStackTrace()
    }
  }

  private val removed = Seq(
    typeOf[GenericVerifySignatureMockup.type],
    typeOf[VerifyTxSignatureMockup.type],
    typeOf[TemplateVariable]
  ).map(_.typeSymbol)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def getAllSubclasses(tpe: Symbol): Set[Symbol] = {
    val directSubclasses = tpe.asClass.knownDirectSubclasses
    directSubclasses.flatMap { cls =>
      if ((cls.isClass && cls.asClass.isCaseClass) || cls.isModuleClass) {
        Set(cls)
      } else {
        getAllSubclasses(cls)
      }
    }
  }

  private val allInstrs = getAllSubclasses(typeOf[Instr[_]].typeSymbol.asType)
    .filter(c => !removed.contains(c.asType))
  if (allInstrs.size != Instr.toCode.size) throw new RuntimeException("failed to get instrs")
  private val instrCodes = Instr.toCode.map { case (instr, code) =>
    (instr.getClass.getSimpleName.stripSuffix("$"), code)
  }

  writeToFile("./generated_rust_instr_decoder.rs", GenRustDecoder.genCode(allInstrs, instrCodes))
  writeToFile("./generated_ts_instr_codec.ts", GenTsCodec.genCode(allInstrs, instrCodes))
}
