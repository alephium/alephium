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

package org.alephium.protocol.vm

import org.scalacheck.Gen

import org.alephium.io.{IOResult, StorageFixture}
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, U256}

class WorldStateSpec extends AlephiumSpec with NoIndexModelGenerators with StorageFixture {
  def generateAsset: Gen[(TxOutputRef, TxOutput)] = {
    for {
      groupIndex     <- groupIndexGen
      assetOutputRef <- assetOutputRefGen(groupIndex)
      assetOutput    <- assetOutputGen(groupIndex)()
    } yield (assetOutputRef, assetOutput)
  }

  def generateContract
      : Gen[(StatefulContract.HalfDecoded, AVector[Val], ContractOutputRef, ContractOutput)] = {
    lazy val counterStateGen: Gen[AVector[Val]] =
      Gen.choose(0L, Long.MaxValue / 1000).map(n => AVector(Val.U256(U256.unsafe(n))))
    for {
      groupIndex    <- groupIndexGen
      outputRef     <- contractOutputRefGen(groupIndex)
      output        <- contractOutputGen(scriptGen = p2cLockupGen(groupIndex))
      contractState <- counterStateGen
    } yield (counterContract.toHalfDecoded(), contractState, outputRef, output)
  }

  // scalastyle:off method.length
  def test[T](initialWorldState: WorldState[T]) = {
    val (assetOutputRef, assetOutput)                    = generateAsset.sample.get
    val (code, state, contractOutputRef, contractOutput) = generateContract.sample.get
    val (_, _, contractOutputRef1, contractOutput1)      = generateContract.sample.get
    val contractId                                       = contractOutputRef.key
    val contractId1                                      = contractOutputRef1.key

    val contractObj = StatefulContractObject(code, Hash.zero, state, contractOutputRef.key)
    var worldState  = initialWorldState

    def update(f: => IOResult[T]) = f.rightValue match {
      case _: Unit                      => ()
      case newWorldState: WorldState[_] => worldState = newWorldState.asInstanceOf[WorldState[T]]
      case _                            => ???
    }

    worldState.getOutput(assetOutputRef).isLeft is true
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractObj(contractOutputRef.key).isLeft is true
    worldState.removeAsset(assetOutputRef).isLeft is true
    worldState.removeAsset(contractOutputRef).isLeft is true

    update(worldState.addAsset(assetOutputRef, assetOutput))
    worldState.getOutput(assetOutputRef) isE assetOutput

    update(
      worldState.createContractUnsafe(
        code,
        Hash.zero,
        state,
        contractOutputRef,
        contractOutput
      )
    )
    worldState.getContractObj(contractOutputRef.key) isE contractObj
    worldState.getContractCode(code.hash) isE WorldState.CodeRecord(code, 1)
    worldState.getOutput(contractOutputRef) isE contractOutput

    update(worldState.removeAsset(assetOutputRef))
    worldState.getOutput(assetOutputRef).isLeft is true

    val newState = AVector[Val](Val.Bool(false))
    assume(newState != state)
    update(
      worldState.createContractUnsafe(
        code,
        Hash.zero,
        newState,
        contractOutputRef1,
        contractOutput1
      )
    )
    worldState.getContractCode(code.hash) isE WorldState.CodeRecord(code, 2)
    worldState.getContractObj(contractId1) isE
      StatefulContractObject(code, contractObj.initialStateHash, newState, contractId1)

    update(worldState.removeContract(contractId))
    worldState.getContractObj(contractId).isLeft is true
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractState(contractId).isLeft is true
    worldState.getContractCode(code.hash) isE WorldState.CodeRecord(code, 1)
    worldState.removeContract(contractId).isLeft is true

    update(worldState.removeContract(contractId1))
    worldState.getContractObj(contractId1).isLeft is true
    worldState.getOutput(contractOutputRef1).isLeft is true
    worldState.getContractState(contractId).isLeft is true
    worldState.getContractCode(code.hash).isLeft is true
    worldState.removeContract(contractId1).isLeft is true
  }

  it should "test mutable world state" in {
    test(WorldState.emptyCached(newDB))
  }

  it should "test immutable world state" in {
    test(WorldState.emptyPersisted(newDB))
  }
}
