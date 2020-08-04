package org.alephium.protocol.vm

import org.scalacheck.Gen

import org.alephium.protocol.ALF
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGenerators}
import org.alephium.util.AVector

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def prepareContract(contract: StatefulContract,
                      fields: AVector[Val]): (StatefulContractObject, StatefulContext) = {
    val groupIndex        = GroupIndex.unsafe(0)
    val contractOutputRef = contractOutputRefGen(groupIndex).sample.get
    val contractOutput    = contractOutputGen(groupIndex)(codeGen = Gen.const(contract)).sample.get
    val worldStateNew =
      cachedWorldState.addContract(contractOutputRef, contractOutput, fields).toOption.get
    val obj     = contract.toObject(contractOutputRef.key, fields)
    val context = StatefulContext(ALF.Hash.zero, worldStateNew)
    obj -> context
  }
}
