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

import org.alephium.io.StorageFixture
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

  def generateContract: Gen[(StatefulContract, AVector[Val], ContractOutputRef, ContractOutput)] = {
    lazy val counterStateGen: Gen[AVector[Val]] =
      Gen.choose(0L, Long.MaxValue / 1000).map(n => AVector(Val.U256(U256.unsafe(n))))
    for {
      groupIndex    <- groupIndexGen
      outputRef     <- contractOutputRefGen(groupIndex)
      output        <- contractOutputGen()
      contractState <- counterStateGen
    } yield (counterContract, contractState, outputRef, output)
  }

  it should "test mutable world state" in {
    val (assetOutputRef, assetOutput)                    = generateAsset.sample.get
    val (code, state, contractOutputRef, contractOutput) = generateContract.sample.get
    val contractKey                                      = contractOutputRef.key

    val contractObj = StatefulContractObject(code, state, state.toArray, contractOutputRef.key)
    val worldState  = WorldState.emptyCached(newDB)

    worldState.getOutput(assetOutputRef).isLeft is true
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractObj(contractOutputRef.key).isLeft is true
    worldState.removeAsset(assetOutputRef).isLeft is true
    worldState.removeAsset(contractOutputRef).isLeft is true

    worldState.addAsset(assetOutputRef, assetOutput) isE ()
    worldState.createContract(code, state, contractOutputRef, contractOutput) isE ()

    worldState.getOutput(assetOutputRef) isE assetOutput
    worldState.getOutput(contractOutputRef) isE contractOutput
    worldState.getContractObj(contractOutputRef.key) isE contractObj

    worldState.removeAsset(assetOutputRef) isE ()
    worldState.removeContract(contractKey) isE ()

    worldState.getOutput(assetOutputRef).isLeft is true
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractObj(contractOutputRef.key).isLeft is true
  }

  it should "test immutable world state" in {

    val (assetOutputRef, assetOutput)                    = generateAsset.sample.get
    val (code, state, contractOutputRef, contractOutput) = generateContract.sample.get
    val contractKey                                      = contractOutputRef.key

    val contractObj = StatefulContractObject(code, state, state.toArray, contractOutputRef.key)
    val worldState  = WorldState.emptyPersisted(newDB)

    worldState.getOutput(assetOutputRef).isLeft is true
    worldState.getOutput(contractOutputRef).isLeft is true
    worldState.getContractObj(contractOutputRef.key).isLeft is true
    worldState.removeAsset(assetOutputRef).isLeft is true
    worldState.removeAsset(contractOutputRef).isLeft is true

    val worldState0 = worldState.addAsset(assetOutputRef, assetOutput).rightValue
    val worldState1 =
      worldState0.createContract(code, state, contractOutputRef, contractOutput).rightValue

    worldState1.getOutput(assetOutputRef) isE assetOutput
    worldState1.getOutput(contractOutputRef) isE contractOutput
    worldState1.getContractObj(contractOutputRef.key) isE contractObj

    val worldState2 = worldState1.removeAsset(assetOutputRef).toOption.get
    val worldState3 = worldState2.removeContract(contractKey).toOption.get
    val worldState4 = worldState3

    worldState4.getOutput(assetOutputRef).isLeft is true
    worldState4.getOutput(contractOutputRef).isLeft is true
    worldState4.getContractObj(contractOutputRef.key).isLeft is true
  }
}
