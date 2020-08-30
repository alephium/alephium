package org.alephium.flow.model

import org.alephium.protocol.model.{BrokerInfo, CliqueId}

sealed trait DataOrigin {
  def isFrom(another: CliqueId): Boolean

  def isFrom(brokerInfo: BrokerInfo): Boolean
}

object DataOrigin {
  case object Local extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = false

    override def isFrom(brokerInfo: BrokerInfo): Boolean = false
  }

  sealed trait FromClique extends DataOrigin {
    def brokerInfo: BrokerInfo

    def cliqueId: CliqueId = brokerInfo.cliqueId

    override def isFrom(another: CliqueId): Boolean = cliqueId == another

    override def isFrom(_brokerInfo: BrokerInfo): Boolean = _brokerInfo == brokerInfo
  }
  final case class InterClique(brokerInfo: BrokerInfo) extends FromClique
  final case class IntraClique(brokerInfo: BrokerInfo) extends FromClique
}
