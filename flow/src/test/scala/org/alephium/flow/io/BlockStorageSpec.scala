package org.alephium.flow.io

import java.nio.file.Files

import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.core.TestUtils
import org.alephium.protocol.config.ConsensusConfigFixture
import org.alephium.protocol.model.ModelGen
import org.alephium.util.{AlephiumSpec, Files => AFiles}

class BlockStorageSpec extends AlephiumSpec {
  trait Fixture {
    val root    = AFiles.tmpDir.resolve(".alephium-test-diskspec")
    val storage = BlockStorage.create(root).right.value

    def postTest(): Assertion = {
      storage.clear()
      Files.exists(root) is true
      Files.exists(storage.folder) is false
    }
  }

  it should "create related folders" in new Fixture {
    Files.exists(root) is true
    Files.exists(storage.folder) is true
    BlockStorage.create(root).isRight is true
  }

  it should "save and read blocks" in new Fixture with ConsensusConfigFixture {
    forAll(ModelGen.blockGen) { block =>
      storage.existsUnsafe(block.hash) is false
      storage.put(block).isRight is true
      storage.existsUnsafe(block.hash) is true
      storage.getUnsafe(block.hash) is block
      storage.get(block.hash) isE block
    }
    TestUtils.clear(root)
  }
}
