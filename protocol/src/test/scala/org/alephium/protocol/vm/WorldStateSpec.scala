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
import org.scalatest.Assertion

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
      output        <- contractOutputGen(groupIndex)()
      contractState <- counterStateGen
    } yield (counterContract, contractState, outputRef, output)
  }

  it should "work" in {
    def test(worldState: WorldState): Assertion = {
      val (assetOutputRef, assetOutput)                    = generateAsset.sample.get
      val (code, state, contractOutputRef, contractOutput) = generateContract.sample.get
      val contractKey                                      = contractOutputRef.key

      val contractObj = StatefulContractObject(code, state, state.toArray, contractOutputRef.key)

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

    test(WorldState.emptyCached(newDB))
    test(WorldState.emptyCached(newDB))
  }
}
