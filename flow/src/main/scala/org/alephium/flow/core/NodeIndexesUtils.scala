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

package org.alephium.flow.core

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.model.{BlockHash, ChainIndex, ContractId, TxOutput, TxOutputRef}
import org.alephium.protocol.vm.nodeindexes.{TxIdTxOutputLocators, TxOutputLocator}
import org.alephium.protocol.vm.subcontractindex.SubContractIndexStateId
import org.alephium.util.AVector

trait NodeIndexesUtils { Self: FlowUtils =>
  def getTxIdTxOutputLocatorsFromOutputRef(
      outputRef: TxOutputRef
  ): IOResult[Option[TxIdTxOutputLocators]] = {
    getTxIdTxOutputLocatorsFromOutputRef(outputRef.key)
  }

  def getTxIdTxOutputLocatorsFromOutputRef(
      outputRefKey: TxOutputRef.Key
  ): IOResult[Option[TxIdTxOutputLocators]] = {
    txOutputRefIndexStorage.flatMap(_.getOpt(outputRefKey))
  }

  def getParentContractId(contractId: ContractId): IOResult[Option[ContractId]] = {
    subContractIndexStorage.flatMap(_.parentContractIndexState.getOpt(contractId))
  }

  def getSubContractIds(
      contractId: ContractId,
      start: Int,
      end: Int
  ): IOResult[(Int, AVector[ContractId])] = {
    assume(start < end)
    val allSubContracts: ArrayBuffer[ContractId] = ArrayBuffer.empty
    var nextCount                                = start

    @tailrec
    def rec(
        subContractIndexStateId: SubContractIndexStateId
    ): IOResult[Unit] = {
      subContractIndexStorage match {
        case Right(storage) =>
          storage.subContractIndexStates.getOpt(subContractIndexStateId) match {
            case Right(Some(subContractIndexState)) =>
              allSubContracts ++= subContractIndexState.subContracts
              nextCount = subContractIndexStateId.counter + 1
              if (nextCount < end) {
                rec(SubContractIndexStateId(subContractIndexStateId.contractId, nextCount))
              } else {
                Right(())
              }
            case Right(None) =>
              Right(())
            case Left(error) =>
              Left(error)

          }
        case Left(error) =>
          Left(error)
      }
    }

    rec(SubContractIndexStateId(contractId, nextCount)).map(_ =>
      (nextCount, AVector.from(allSubContracts))
    )
  }

  def getSubContractsCurrentCount(parentContractId: ContractId): IOResult[Option[Int]] = {
    subContractIndexStorage.flatMap(_.subContractIndexCounterState.getOpt(parentContractId))
  }

  def getTxOutput(outputRef: TxOutputRef, spentBlockHash: BlockHash): IOResult[Option[TxOutput]] = {
    getTxOutput(outputRef, spentBlockHash, maxForkDepth)
  }

  def getTxOutput(
      outputRef: TxOutputRef,
      spentBlockHash: BlockHash,
      maxForkDepth: Int
  ): IOResult[Option[TxOutput]] = {
    for {
      resultOpt <- getTxIdTxOutputLocatorsFromOutputRef(outputRef)
      txOutputOpt <- resultOpt match {
        case Some(TxIdTxOutputLocators(_, txOutputLocators)) =>
          for {
            locator <- getOutputLocator(blockFlow, spentBlockHash, txOutputLocators, maxForkDepth)
            block   <- blockFlow.getBlock(locator.blockHash)
          } yield Some(block.getTransaction(locator.txIndex).getOutput(locator.txOutputIndex))
        case None =>
          Right(None)
      }
    } yield {
      txOutputOpt
    }
  }

  private def getOutputLocator(
      blockFlow: BlockFlow,
      spentBlockHash: BlockHash,
      locators: AVector[TxOutputLocator],
      maxForkDepth: Int
  ): IOResult[TxOutputLocator] = {
    assume(locators.nonEmpty)
    // There is only one locator, must be it!
    if (locators.length == 1) {
      Right(locators(0))
    } else {
      for {
        spentBlockHeight <- blockFlow.getHeight(spentBlockHash)
        partitioned <- locators.partitionE(locator =>
          blockFlow.getHeight(locator.blockHash).map(spentBlockHeight - _ > maxForkDepth)
        )
        (deepLocators, shallowLocators) = partitioned
        deepMainChainLocators <- deepLocators.filterE(p => isBlockInMainChain(p.blockHash))
        locator <-
          if (deepMainChainLocators.nonEmpty) {
            // When there are deep locators, we only take the mainchain locator
            Right(deepMainChainLocators.head)
          } else if (shallowLocators.length == 1) {
            Right(shallowLocators.head)
          } else {
            getOutputLocatorSlowly(blockFlow, spentBlockHash, shallowLocators)
          }
      } yield locator
    }
  }

  private def getOutputLocatorSlowly(
      blockFlow: BlockFlow,
      spentBlockHash: BlockHash,
      locators: AVector[TxOutputLocator]
  ): IOResult[TxOutputLocator] = {
    locators
      .findE(locator => isBefore(blockFlow, locator.blockHash, spentBlockHash))
      .flatMap {
        case Some(locator) => Right(locator)
        case None          => Left(IOError.keyNotFound("Cannot find the input info for the TX"))
      }
  }

  private[core] def isBefore(
      blockFlow: BlockFlow,
      previous: BlockHash,
      current: BlockHash
  ): IOResult[Boolean] = {
    val previousChainIndex = ChainIndex.from(previous)
    val currentChainIndex  = ChainIndex.from(current)

    val chain = blockFlow.getHeaderChain(currentChainIndex)
    if (currentChainIndex == previousChainIndex) {
      chain.isBefore(previous, current)
    } else {
      val groupDeps = getOutTipsUnsafe(current, previousChainIndex.from)
      chain.isBefore(previous, groupDeps(previousChainIndex.to.value))
    }
  }
}
