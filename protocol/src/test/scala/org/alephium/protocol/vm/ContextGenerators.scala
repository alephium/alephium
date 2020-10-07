package org.alephium.protocol.vm

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGenerators}
import org.alephium.util.AVector

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def prepareContract(contract: StatefulContract,
                      fields: AVector[Val]): (StatefulContractObject, StatefulContext) = {
    val groupIndex        = GroupIndex.unsafe(0)
    val contractOutputRef = contractOutputRefGen(groupIndex).sample.get
    val contractOutput    = contractOutputGen(groupIndex)().sample.get
    val worldStateNew =
      cachedWorldState
        .createContract(contract, fields, contractOutputRef, contractOutput)
        .toOption
        .get
    val obj     = contract.toObject(contractOutputRef.key, fields)
    val context = StatefulContext.nonPayable(Hash.zero, worldStateNew)
    obj -> context
  }
}
