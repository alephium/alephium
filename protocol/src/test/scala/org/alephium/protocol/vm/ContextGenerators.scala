package org.alephium.protocol.vm

import org.alephium.protocol.Hash
import org.alephium.protocol.model.{GroupIndex, NoIndexModelGenerators}
import org.alephium.util.AVector

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def prepareContract(contract: StatefulContract,
                      fields: AVector[Val]): (StatefulContractObject, StatefulContext) = {
    val groupIndex     = GroupIndex.unsafe(0)
    val assetOutputRef = contractOutputRefGen(groupIndex).sample.get
    val assetOutput    = contractOutputGen(groupIndex)().sample.get
    val worldStateNew =
      cachedWorldState.createContract(contract, fields, assetOutputRef, assetOutput).toOption.get
    val obj     = contract.toObject(assetOutputRef.key, fields)
    val context = StatefulContext.nonPayable(Hash.zero, worldStateNew)
    obj -> context
  }
}
