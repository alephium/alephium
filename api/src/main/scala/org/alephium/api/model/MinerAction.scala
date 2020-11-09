package org.alephium.api.model

trait MinerAction
  object MinerAction {
    case object StartMining extends MinerAction
    case object StopMining  extends MinerAction
  }
