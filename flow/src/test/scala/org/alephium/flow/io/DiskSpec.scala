package org.alephium.flow.io

import java.nio.file.Files

import org.scalatest.EitherValues._

import org.alephium.flow.storage.TestUtils
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.ModelGen
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Files => AFiles}

class DiskSpec extends AlephiumSpec {

  trait Fixture {
    val root = AFiles.tmpDir.resolve(".alephium-test-diskspec")
    val disk = Disk.create(root).right.value
  }

  it should "create related folders" in new Fixture {
    Files.exists(root) is true
    Files.exists(disk.blockFolder) is true
    Disk.create(root).isRight is true

    TestUtils.clear(root)
    Files.exists(root) is true
    Files.exists(disk.blockFolder) is false
  }

  it should "save and read blocks" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.blockGen) { block =>
      disk.checkBlockFile(block.hash) is false
      val data = serialize(block)
      disk.putBlock(block).right.value is data.length
      disk.checkBlockFile(block.hash) is true
      disk.getBlock(block.hash).right.value is block
    }
    TestUtils.clear(root)
  }
}
