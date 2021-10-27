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

import org.alephium.flow.core.BlockFlow
import org.alephium.protocol.{ALPH, BlockHash, Hash}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model._
import org.alephium.util.TimeStamp

trait HeaderValidation extends Validation[BlockHeader, InvalidHeaderStatus, Unit] {
  def validate(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit] = {
    checkHeader(header, flow)
  }

  def validateUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow
  ): HeaderValidationResult[Unit] = {
    checkHeaderUntilDependencies(header, flow)
  }

  def validateAfterDependencies(
      header: BlockHeader,
      flow: BlockFlow
  ): HeaderValidationResult[Unit] = {
    checkHeaderAfterDependencies(header, flow)
  }

  protected[validation] def validateGenesisHeader(
      genesis: BlockHeader
  ): HeaderValidationResult[Unit] = {
    for {
      _ <- checkGenesisVersion(genesis)
      _ <- checkGenesisTimeStamp(genesis)
      _ <- checkGenesisDependencies(genesis)
      _ <- checkGenesisDepStateHash(genesis)
      _ <- checkGenesisWorkAmount(genesis)
      _ <- checkGenesisWorkTarget(genesis)
    } yield ()
  }

  protected[validation] def checkHeader(
      header: BlockHeader,
      flow: BlockFlow
  ): HeaderValidationResult[Unit] = {
    for {
      _ <- checkHeaderUntilDependencies(header, flow)
      _ <- checkHeaderAfterDependencies(header, flow)
    } yield ()
  }

  protected[validation] def checkHeaderUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow
  ): HeaderValidationResult[Unit] = {
    for {
      _      <- checkVersion(header)
      parent <- getParentHeader(flow, header) // parent should exist as checked in ChainHandler
      _      <- checkTimeStampIncreasing(header, parent)
      _      <- checkTimeStampDrift(header)
      _      <- checkDepsNum(header)
      _      <- checkDepsIndex(header)
      _      <- checkWorkAmount(header)
      _      <- checkDepsMissing(header, flow)
      _      <- checkWorkTarget(header, flow)
      _      <- checkDepStateHash(header, flow)
    } yield ()
  }

  protected[validation] def checkHeaderAfterDependencies(
      header: BlockHeader,
      flow: BlockFlow
  ): HeaderValidationResult[Unit] = {
    for {
      _ <- checkUncleDepsTimeStamp(header, flow)
      _ <- checkFlow(header, flow)
    } yield ()
  }

  protected[validation] def getParentHeader(
      blockFlow: BlockFlow,
      header: BlockHeader
  ): HeaderValidationResult[BlockHeader] = {
    ValidationStatus.from(blockFlow.getBlockHeader(header.parentHash))
  }

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkGenesisVersion(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisTimeStamp(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisDependencies(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisDepStateHash(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisWorkAmount(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisWorkTarget(header: BlockHeader): HeaderValidationResult[Unit]

  protected[validation] def checkVersion(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkTimeStampIncreasing(header: BlockHeader, parent: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkTimeStampDrift(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkWorkAmount(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkDepsNum(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkDepsIndex(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkDepsMissing(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit]
  protected[validation] def checkDepStateHash(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit]
  protected[validation] def checkWorkTarget(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit]
  protected[validation] def checkUncleDepsTimeStamp(header: BlockHeader, flow: BlockFlow)(implicit brokerConfig: BrokerConfig): HeaderValidationResult[Unit]
  protected[validation] def checkFlow(header: BlockHeader, flow: BlockFlow)(implicit brokerConfig: BrokerConfig): HeaderValidationResult[Unit]
  // format: on
}

object HeaderValidation {
  import ValidationStatus._

  def build(implicit
      brokerConfig: BrokerConfig,
      consensusConfig: ConsensusConfig
  ): HeaderValidation = new Impl()

  final class Impl(implicit val brokerConfig: BrokerConfig, val consensusConfig: ConsensusConfig)
      extends HeaderValidation {
    protected[validation] def checkGenesisVersion(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      if (header.version == DefaultBlockVersion) {
        validHeader(())
      } else {
        invalidHeader(InvalidGenesisVersion)
      }
    }
    protected[validation] def checkGenesisTimeStamp(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      if (header.timestamp != ALPH.GenesisTimestamp) {
        invalidHeader(InvalidGenesisTimeStamp)
      } else {
        validHeader(())
      }
    }
    protected[validation] def checkGenesisDependencies(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      val deps = header.blockDeps.deps
      if (deps.length == brokerConfig.depsNum && deps.forall(_ == BlockHash.zero)) {
        validHeader(())
      } else {
        invalidHeader(InvalidGenesisDeps)
      }
    }
    protected[validation] def checkGenesisDepStateHash(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      if (header.depStateHash == Hash.zero) {
        validHeader(())
      } else {
        invalidHeader(InvalidGenesisDepStateHash)
      }
    }
    protected[validation] def checkGenesisWorkAmount(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      validHeader(()) // we don't check work for genesis headers
    }
    protected[validation] def checkGenesisWorkTarget(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      if (header.target != consensusConfig.maxMiningTarget) {
        invalidHeader(InvalidGenesisWorkTarget)
      } else {
        validHeader(())
      }
    }

    protected[validation] def checkVersion(header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.version == DefaultBlockVersion) {
        validHeader(())
      } else {
        invalidHeader(InvalidBlockVersion)
      }
    }

    protected[validation] def checkTimeStampIncreasing(
        header: BlockHeader,
        parent: BlockHeader
    ): HeaderValidationResult[Unit] = {
      if (header.timestamp < ALPH.LaunchTimestamp) {
        invalidHeader(EarlierThanLaunchTimeStamp)
      } else if (header.timestamp <= parent.timestamp) {
        invalidHeader(NoIncreasingTimeStamp)
      } else {
        validHeader(())
      }
    }
    protected[validation] def checkTimeStampDrift(
        header: BlockHeader
    ): HeaderValidationResult[Unit] = {
      if (TimeStamp.now() + consensusConfig.maxHeaderTimeStampDrift <= header.timestamp) {
        invalidHeader(TooAdvancedTimeStamp)
      } else {
        validHeader(())
      }
    }

    protected[validation] def checkWorkAmount(header: BlockHeader): HeaderValidationResult[Unit] = {
      if (PoW.checkWork(header)) validHeader(()) else invalidHeader(InvalidWorkAmount)
    }

    protected[validation] def checkDepsNum(header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.blockDeps.length == brokerConfig.depsNum) {
        validHeader(())
      } else {
        invalidHeader(InvalidDepsNum)
      }
    }

    protected[validation] def checkDepsIndex(header: BlockHeader): HeaderValidationResult[Unit] = {
      val headerIndex = header.chainIndex
      val ok1 = header.inDeps.forallWithIndex { case (dep, index) =>
        val depIndex = ChainIndex.from(dep)
        if (index < headerIndex.from.value) {
          depIndex.from.value == index && depIndex.to.value == index
        } else {
          val expected = index + 1
          depIndex.from.value == expected && depIndex.to.value == expected
        }
      }
      val ok2 = header.outDeps.forallWithIndex { case (dep, index) =>
        val depIndex = ChainIndex.from(dep)
        depIndex.from == headerIndex.from && depIndex.to.value == index
      }
      if (ok1 && ok2) validHeader(()) else invalidHeader(InvalidDepsIndex)
    }

    protected[validation] def checkDepsMissing(
        header: BlockHeader,
        flow: BlockFlow
    ): HeaderValidationResult[Unit] = {
      ValidationStatus.from(header.blockDeps.deps.filterNotE(flow.contains)).flatMap { missings =>
        if (missings.isEmpty) validHeader(()) else invalidHeader(MissingDeps(missings))
      }
    }

    protected[validation] def checkDepStateHash(
        header: BlockHeader,
        flow: BlockFlow
    ): HeaderValidationResult[Unit] = {
      if (brokerConfig.contains(header.chainIndex.from)) {
        ValidationStatus
          .from(flow.getDepStateHash(header))
          .flatMap { stateHash =>
            if (stateHash == header.depStateHash) {
              validHeader(())
            } else {
              invalidHeader(InvalidDepStateHash)
            }
          }
      } else {
        validHeader(())
      }
    }

    protected[validation] def checkWorkTarget(
        header: BlockHeader,
        blockFlow: BlockFlow
    ): HeaderValidationResult[Unit] = {
      ValidationStatus
        .from(blockFlow.getNextHashTarget(header.chainIndex, header.blockDeps))
        .flatMap { target =>
          if (target == header.target) validHeader(()) else invalidHeader(InvalidWorkTarget)
        }
    }

    protected[validation] def checkUncleDepsTimeStamp(header: BlockHeader, flow: BlockFlow)(implicit
        brokerConfig: BrokerConfig
    ): HeaderValidationResult[Unit] = {
      val thresholdTs = header.timestamp.minusUnsafe(consensusConfig.uncleDependencyGapTime)
      val headerIndex = header.chainIndex
      val resultEither = header.blockDeps.deps.forallE { dep =>
        val depIndex = ChainIndex.from(dep)
        if (depIndex != headerIndex) {
          flow.getBlockHeader(dep).map(_.timestamp <= thresholdTs)
        } else {
          Right(true)
        }
      }
      ValidationStatus.from(resultEither).flatMap { ok =>
        if (ok) validHeader(()) else invalidHeader(InvalidUncleTimeStamp)
      }
    }

    protected[validation] def checkFlow(header: BlockHeader, flow: BlockFlow)(implicit
        brokerConfig: BrokerConfig
    ): HeaderValidationResult[Unit] = {
      ValidationStatus.from(flow.checkFlowDeps(header)).flatMap { ok =>
        if (ok) validHeader(()) else invalidHeader(InvalidHeaderFlow)
      }
    }
  }
}
