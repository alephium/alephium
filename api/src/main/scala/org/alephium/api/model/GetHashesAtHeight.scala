package org.alephium.api.model

import org.alephium.api.ApiModel.PerChain

final case class GetHashesAtHeight(val fromGroup: Int, val toGroup: Int, height: Int) extends PerChain
