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

package org.alephium.flow.validation

import java.math.BigInteger

import org.scalacheck.Gen
import org.scalatest.Assertion
import org.scalatest.EitherValues._

import org.alephium.flow.{AlephiumFlowSpec, FlowFixture}
import org.alephium.protocol.{ALPH, BlockHash, Hash}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Duration}

class HeaderValidationSpec extends AlephiumFlowSpec with NoIndexModelGeneratorsLike {
  trait Fixture {
    def passCheck[T](result: HeaderValidationResult[T]): Assertion = {
      result.isRight is true
    }

    def failCheck[T](result: HeaderValidationResult[T], error: InvalidHeaderStatus): Assertion = {
      result.left.value isE error
    }

    def passValidation(result: HeaderValidationResult[Unit]): Assertion = {
      result.isRight is true
    }

    def failValidation(
        result: HeaderValidationResult[Unit],
        error: InvalidHeaderStatus
    ): Assertion = {
      result.left.value isE error
    }
  }

  behavior of "genesis validation"

  trait GenesisFixture extends Fixture {
    val chainIndex      = ChainIndex.unsafe(1, 2)
    val genesis         = BlockHeader.genesis(chainIndex, Hash.zero)
    val headerValidator = HeaderValidation.build

    def passValidation(header: BlockHeader): Assertion = {
      passValidation(headerValidator.validateGenesisHeader(header))
    }

    def failValidation(header: BlockHeader, error: InvalidHeaderStatus): Assertion = {
      failValidation(headerValidator.validateGenesisHeader(header), error)
    }
  }

  it should "validate correct genesis" in new GenesisFixture {
    passValidation(genesis)
  }

  it should "check genesis version" in new GenesisFixture {
    genesis.version is DefaultBlockVersion

    forAll { byte: Byte =>
      whenever(byte != DefaultBlockVersion) {
        val header = genesis.copy(version = byte)
        failValidation(headerValidator.validateGenesisHeader(header), InvalidGenesisVersion)
      }
    }
  }

  it should "check genesis timestamp" in new GenesisFixture {
    genesis.timestamp is ALPH.GenesisTimestamp

    val modified = genesis.copy(timestamp = ALPH.GenesisTimestamp.plusMillisUnsafe(1))
    failValidation(modified, InvalidGenesisTimeStamp)
  }

  it should "check genesis dependencies length" in new GenesisFixture {
    genesis.blockDeps.length is groupConfig.depsNum

    val correctDeps = genesis.blockDeps.deps
    correctDeps.indices.foreach { k =>
      val modified = genesis.copy(blockDeps = BlockDeps.unsafe(correctDeps.take(k)))
      failValidation(modified, InvalidGenesisDeps)
    }
    val modified = genesis.copy(blockDeps = BlockDeps.unsafe(correctDeps :+ correctDeps.head))
    failValidation(modified, InvalidGenesisDeps)
  }

  it should "check genesis dependencies to be zero" in new GenesisFixture {
    genesis.blockDeps.deps.foreach(_ is BlockHash.zero)

    val correctDeps = genesis.blockDeps.deps
    val nonZeroHash = BlockHash.hash(1)
    correctDeps.indices.foreach { k =>
      val modified = genesis.copy(blockDeps = BlockDeps.unsafe(correctDeps.replace(k, nonZeroHash)))
      failValidation(modified, InvalidGenesisDeps)
    }
  }

  it should "check genesis state hash to be zero" in new GenesisFixture {
    genesis.depStateHash is Hash.zero

    val modified = genesis.copy(depStateHash = Hash.generate)
    failValidation(modified, InvalidGenesisDepStateHash)
  }

  it should "allow arbitrary genesis PoW work" in new GenesisFixture {
    forAll(nonceGen) { nonce =>
      val modified = genesis.copy(nonce = nonce)
      passCheck(headerValidator.checkGenesisWorkAmount(modified))
    }
  }

  it should "check genesis PoW target" in new GenesisFixture {
    genesis.target is consensusConfig.maxMiningTarget

    val modified = genesis.copy(target = Target.unsafe(BigInteger.ZERO))
    failValidation(headerValidator.validateGenesisHeader(modified), InvalidGenesisWorkTarget)
  }

  behavior of "normal header validation"

  trait HeaderFixture extends Fixture with FlowFixture {
    override val configValues = Map(
      ("alephium.broker.broker-num", 1),
      ("alephium.consensus.num-zeros-at-least-in-hash", 1)
    )

    val chainIndex = ChainIndex.unsafe(1, 2)
    val header0 = {
      val block0 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block0)
      block0.header
    }
    val header = {
      val block1 = emptyBlock(blockFlow, chainIndex)
      addAndCheck(blockFlow, block1)
      block1.header
    }
    val headerValidator = HeaderValidation.build
    headerValidator.getParentHeader(blockFlow, header) isE header0

    def updateNonce(modified: BlockHeader): BlockHeader = {
      nonceGen
        .map(nonce => modified.copy(nonce = nonce))
        .retryUntil { newHeader =>
          (newHeader.chainIndex equals chainIndex) &&
          (new BigInteger(1, newHeader.hash.bytes.toArray).compareTo(newHeader.target.value) <= 0)
        }
        .sample
        .get
    }

    def updateNonceForIndex(modified: BlockHeader): BlockHeader = {
      nonceGen
        .map(nonce => modified.copy(nonce = nonce))
        .retryUntil { newHeader => newHeader.chainIndex equals chainIndex }
        .sample
        .get
    }

    def passValidation(header: BlockHeader): Assertion = {
      passValidation(headerValidator.validate(header, blockFlow))
    }

    def failValidation(header: BlockHeader, error: InvalidHeaderStatus): Assertion = {
      failValidation(headerValidator.validate(header, blockFlow), error)
    }
  }

  it should "validate correct header" in new HeaderFixture {
    passValidation(header)
  }

  it should "check header version" in new HeaderFixture {
    val modified0 = updateNonce(header.copy(version = DefaultBlockVersion))
    passValidation(modified0)
  }

  it should "check header timestamp increasing" in new HeaderFixture {
    val modified0 = updateNonce(header.copy(timestamp = header0.timestamp))
    failValidation(modified0, NoIncreasingTimeStamp)

    val modified1 = updateNonce(header.copy(timestamp = ALPH.LaunchTimestamp.plusMillisUnsafe(-1)))
    failValidation(modified1, EarlierThanLaunchTimeStamp)
  }

  it should "check timestamp drift" in new HeaderFixture {
    val newTs = header.timestamp + consensusConfig.maxHeaderTimeStampDrift +
      Duration.ofSecondsUnsafe(2)
    val modified = updateNonce(header.copy(timestamp = newTs))
    failValidation(modified, TooAdvancedTimeStamp)
  }

  it should "check dependencies length" in new HeaderFixture {
    header.blockDeps.length is groupConfig.depsNum

    val correctDeps = header.blockDeps.deps
    val modified =
      updateNonce(header.copy(blockDeps = BlockDeps.unsafe(correctDeps :+ correctDeps.head)))
    failValidation(modified, InvalidDepsNum)
  }

  it should "check dependencies indexes" in new HeaderFixture {
    val correctDeps = header.blockDeps.deps
    correctDeps.indices.tail.foreach { k =>
      val modified0 = updateNonce(
        header.copy(blockDeps = BlockDeps.unsafe(correctDeps.replace(k, correctDeps(k - 1))))
      )
      failValidation(modified0, InvalidDepsIndex)

      val modified1 = updateNonce(
        header.copy(blockDeps = BlockDeps.unsafe(correctDeps.replace(k - 1, correctDeps(k))))
      )
      failValidation(modified1, InvalidDepsIndex)
    }
  }

  it should "check PoW work amount" in new HeaderFixture {
    val modified = nonceGen
      .map(nonce => header.copy(nonce = nonce))
      .retryUntil { modified =>
        val work = new BigInteger(1, modified.hash.bytes.toArray)
        (modified.chainIndex equals chainIndex) && (work.compareTo(header.target.value) > 0)
      }
      .sample
      .get
    failValidation(modified, InvalidWorkAmount)
  }

  it should "check PoW work target" in new HeaderFixture {
    val target0   = Target.unsafe(header.target.value.multiply(BigInteger.valueOf(2)))
    val modified0 = header.copy(target = target0)
    failValidation(updateNonce(modified0), InvalidWorkTarget)

    val target1   = Target.unsafe(header.target.value.divide(BigInteger.valueOf(2)))
    val modified1 = header.copy(target = target1)
    failValidation(updateNonce(modified1), InvalidWorkTarget)
  }

  it should "check dependencies missing" in new HeaderFixture {
    val correctDeps = header.blockDeps.deps
    forAll(Gen.someOf(correctDeps.indices.filter(_ != groups0 + 1))) { depIndexes =>
      if (depIndexes.nonEmpty) {
        val newDeps = depIndexes.foldLeft(correctDeps) { case (deps, k) =>
          val newDep = Gen
            .resultOf[Unit, BlockHash](_ => BlockHash.random)
            .retryUntil(hash => ChainIndex.from(hash) equals ChainIndex.from(correctDeps(k)))
          deps.replace(k, newDep.sample.get)
        }
        val modified =
          mineHeader(
            header.chainIndex,
            newDeps,
            Hash.zero,
            header.txsHash,
            header.timestamp,
            header.target
          )
        failValidation(modified, MissingDeps(AVector.from(depIndexes.sorted.map(newDeps.apply))))
      }
    }
  }

  it should "check state hash" in new HeaderFixture {
    val modified0 = header.copy(depStateHash = Hash.zero)
    failValidation(updateNonce(modified0), InvalidDepStateHash)

    val modified1 = header.copy(depStateHash = Hash.generate)
    failValidation(updateNonce(modified1), InvalidDepStateHash)
  }
}
