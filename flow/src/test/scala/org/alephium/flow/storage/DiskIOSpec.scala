package org.alephium.flow.storage

import java.nio.file.Files

import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.ModelGen
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, Files => AFiles}
import org.scalatest.EitherValues._

class DiskIOSpec extends AlephiumSpec {

  trait Fixture {
    val root   = AFiles.tmpDir.resolve(".alephium-test")
    val diskIO = DiskIO.create(root).right.value
  }

  it should "create related folders" in new Fixture {
    Files.exists(root) is true
    Files.exists(diskIO.blockFolder) is true
    DiskIO.create(root).isRight is true

    TestUtils.cleanup(root)
    Files.exists(root) is false
    Files.exists(diskIO.blockFolder) is false
  }

  it should "save and read blocks" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.blockGen) { block =>
      diskIO.checkBlockFile(block.hash) is false
      val data = serialize(block)
      diskIO.putBlock(block).right.value is data.length
      diskIO.checkBlockFile(block.hash) is true
      diskIO.getBlock(block.hash).right.value is block
    }
    TestUtils.cleanup(root)
  }
}
