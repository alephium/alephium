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

import org.alephium.flow.core.{BlockFlow, BlockHeaderChain}
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.mining.PoW
import org.alephium.protocol.model.BlockHeader
import org.alephium.protocol.BlockHash
import org.alephium.util.TimeStamp

trait HeaderValidation extends Validation[BlockHeader, InvalidHeaderStatus] {
  def validate(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit] = {
    checkHeader(header, flow)
  }

  protected[validation] def checkHeader(header: BlockHeader,
                                        flow: BlockFlow): HeaderValidationResult[Unit] = {
    for {
      _ <- checkHeaderUntilDependencies(header, flow)
      _ <- checkHeaderAfterDependencies(header, flow)
    } yield ()
  }

  def validateUntilDependencies(header: BlockHeader,
                                flow: BlockFlow): HeaderValidationResult[Unit] = {
    checkHeaderUntilDependencies(header, flow)
  }

  def validateAfterDependencies(header: BlockHeader,
                                flow: BlockFlow): HeaderValidationResult[Unit] = {
    checkHeaderAfterDependencies(header, flow)
  }

  protected[validation] def checkHeaderUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow): HeaderValidationResult[Unit] = {
    for {
      parent <- getParentHeader(flow, header)
      _      <- checkTimeStampIncreasing(header, parent)
      _      <- checkTimeStampDrift(header)
      _      <- checkWorkAmount(header)
      _      <- checkDependencies(header, flow)
    } yield ()
  }

  protected[validation] def checkHeaderAfterDependencies(
      header: BlockHeader,
      flow: BlockFlow): HeaderValidationResult[Unit] = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
      _ <- checkFlow(header, flow)
    } yield ()
  }

  protected[validation] def getParentHeader(
      blockFlow: BlockFlow,
      header: BlockHeader): HeaderValidationResult[BlockHeader]

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkGenesisTimeStamp(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisDependencies(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisWorkAmount(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisWorkTarget(header: BlockHeader): HeaderValidationResult[Unit]

  protected[validation] def checkTimeStampIncreasing(header: BlockHeader, parent: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkTimeStampDrift(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkWorkAmount(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkDependencies(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit]
  protected[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain): HeaderValidationResult[Unit]
  protected[validation] def checkFlow(header: BlockHeader, flow: BlockFlow)(implicit brokerConfig: BrokerConfig): HeaderValidationResult[Unit]
  // format: on
}

object HeaderValidation {
  import ValidationStatus._

  def build(implicit brokerConfig: BrokerConfig,
            consensusConfig: ConsensusConfig): HeaderValidation = new Impl()

  final class Impl(implicit val brokerConfig: BrokerConfig, val consensusConfig: ConsensusConfig)
      extends HeaderValidation {
    protected[validation] def checkGenesisTimeStamp(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.timestamp != TimeStamp.zero) {
        invalidHeader(InvalidGenesisTimeStamp)
      } else {
        validHeader(())
      }
    }
    protected[validation] def checkGenesisDependencies(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      val deps = header.blockDeps.deps
      if (deps.length == brokerConfig.depsNum && deps.forall(_ == BlockHash.zero)) {
        validHeader(())
      } else {
        invalidHeader(InvalidGenesisDeps)
      }
    }
    protected[validation] def checkGenesisWorkAmount(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      validHeader(()) // TODO: validate this when initial target is configurable
    }
    protected[validation] def checkGenesisWorkTarget(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.target != consensusConfig.maxMiningTarget) {
        invalidHeader(InvalidGenesisWorkTarget)
      } else {
        validHeader(())
      }
    }

    protected[validation] def getParentHeader(
        blockFlow: BlockFlow,
        header: BlockHeader): HeaderValidationResult[BlockHeader] = {
      ValidationStatus.from(blockFlow.getBlockHeader(header.parentHash))
    }

    protected[validation] def checkTimeStampIncreasing(
        header: BlockHeader,
        parent: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.timestamp <= parent.timestamp) {
        invalidHeader(NoIncreasingTimeStamp)
      } else {
        validHeader(())
      }
    }
    protected[validation] def checkTimeStampDrift(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (TimeStamp.now() + consensusConfig.maxHeaderTimeStampDrift <= header.timestamp) {
        invalidHeader(TooAdvancedTimeStamp)
      } else {
        validHeader(())
      }
    }

    protected[validation] def checkWorkAmount(header: BlockHeader): HeaderValidationResult[Unit] = {
      if (PoW.checkWork(header)) validHeader(()) else invalidHeader(InvalidWorkAmount)
    }

    // TODO: check algorithm validatity of dependencies
    protected[validation] def checkDependencies(header: BlockHeader,
                                                flow: BlockFlow): HeaderValidationResult[Unit] = {
      ValidationStatus.from(header.blockDeps.deps.filterNotE(flow.contains)).flatMap { missings =>
        if (missings.isEmpty) validHeader(()) else invalidHeader(MissingDeps(missings))
      }
    }

    protected[validation] def checkWorkTarget(
        header: BlockHeader,
        headerChain: BlockHeaderChain): HeaderValidationResult[Unit] = {
      ValidationStatus.from(headerChain.getHashTarget(header.parentHash)).flatMap { target =>
        if (target == header.target) validHeader(()) else invalidHeader(InvalidWorkTarget)
      }
    }

    protected[validation] def checkFlow(header: BlockHeader, flow: BlockFlow)(
        implicit brokerConfig: BrokerConfig): HeaderValidationResult[Unit] = {
      if (!brokerConfig.contains(header.chainIndex.from)) {
        ValidationStatus.from(flow.checkFlowDeps(header)).flatMap { ok =>
          if (ok) validHeader(()) else invalidHeader(InvalidHeaderFlow)
        }
      } else {
        validHeader(())
      }
    }
  }
}
