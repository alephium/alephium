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

package org.alephium.protocol.model

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import akka.util.ByteString
import org.scalatest.OptionValues

import org.alephium.serde._
import org.alephium.util.{AlephiumFixture, Hex}

trait ModelSnapshots extends AlephiumFixture with OptionValues {
  def readFile(path: Path): String = {
    val all = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    all.filterNot(_.isWhitespace)
  }

  implicit class SnapshotVerifier[T: Serde](model: T)(implicit
      baseDir: String
  ) {
    def verify(name: String): ByteString = {
      val filePath = Paths.get(s"$baseDir/$name.serialized.txt")
      if (!Files.exists(filePath)) {
        serializeAndWrite(filePath)
      }

      val serialized = Hex.from(readFile(filePath)).value

      serialize(model) is serialized
      deserialize[T](serialized) isE model

      serialized
    }

    def fail(name: String): ByteString = {
      val filePath   = Paths.get(s"$baseDir/$name.serialized.txt")
      val serialized = Hex.from(readFile(filePath)).value

      serialize(model) isnot serialized
      deserialize[T](serialized) isnotE model

      serialized
    }

    // Use this when the serialized format on disk *should* be updated.
    def update(name: String): Path = {
      val filePath = Paths.get(s"$baseDir/$name.serialized.txt")
      serializeAndWrite(filePath)
    }

    private def serializeAndWrite(filePath: Path): Path = {
      val serializedHex = Hex.toHexString(serialize(model))
      Files.createDirectories(filePath.getParent())
      val bytes = serializedHex.grouped(32).mkString("\n").getBytes()
      Files.write(filePath, bytes)
    }
  }
}
