package org.alephium.protocol.vm

import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.io.StorageFixture
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector}

class WorldStateSpec extends AlephiumSpec with NoIndexModelGenerators with StorageFixture {
  def generateAsset: Gen[(AssetOutputRef, AssetOutput)] = {
    for {
      groupIndex     <- groupIndexGen
      assetOutputRef <- assetOutputRefGen(groupIndex)
      assetOutput    <- assetOutputGen(groupIndex)()
    } yield (assetOutputRef, assetOutput)
  }

  def generateContract: Gen[(ContractOutputRef, ContractOutput, AVector[Val])] = {
    for {
      groupIndex        <- groupIndexGen
      contractOutputRef <- contractOutputRefGen
      contractOutput    <- contractOutputGen(groupIndex)()
      contractState     <- counterStateGen
    } yield (contractOutputRef, contractOutput, contractState)
  }

  it should "work" in {
    def test(worldState: WorldState, persist: Boolean): Assertion = {
      val (assetOutputRef, assetOutput)              = generateAsset.sample.get
      val (contractOutputRef, contractOutput, state) = generateContract.sample.get
      val contractObj =
        StatefulContractObject(contractOutput.code, state.toArray, contractOutputRef.key)

      worldState.getOutput(assetOutputRef).isLeft is true
      worldState.getOutput(contractOutputRef).isLeft is true
      worldState.getContractObj(contractOutputRef.key).isLeft is true
      if (worldState.isInstanceOf[WorldState.Persisted]) {
        worldState.remove(assetOutputRef).isLeft is true
        worldState.remove(contractOutputRef).isLeft is true
      }

      val worldState0 = worldState.addAsset(assetOutputRef, assetOutput).toOption.get
      val worldState1 =
        worldState0.addContract(contractOutputRef, contractOutput, state).toOption.get
      val worldState2 = if (persist) worldState1.persist.toOption.get else worldState1

      worldState2.getOutput(assetOutputRef) isE assetOutput
      worldState2.getOutput(contractOutputRef) isE contractOutput
      worldState2.getContractObj(contractOutputRef.key) isE contractObj

      val worldState3 = worldState2.remove(assetOutputRef).toOption.get
      val worldState4 = worldState3.remove(contractOutputRef).toOption.get
      val worldState5 = if (persist) worldState4.persist.toOption.get else worldState4

      worldState5.getOutput(assetOutputRef).isLeft is true
      worldState5.getOutput(contractOutputRef).isLeft is true
      worldState5.getContractObj(contractOutputRef.key).isLeft is true
    }

    test(WorldState.emptyPersisted(newDB), true)
    test(WorldState.emptyPersisted(newDB), false)
    test(WorldState.emptyCached(newDB), true)
    test(WorldState.emptyCached(newDB), false)
  }
}
