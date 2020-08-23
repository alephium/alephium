package org.alephium.flow.model

import org.alephium.protocol.model.{BrokerInfo, CliqueId}

sealed trait DataOrigin {
  def isFrom(another: CliqueId): Boolean

  def isFrom(cliqueId: CliqueId, brokerInfo: BrokerInfo): Boolean
}

object DataOrigin {
  case object Local extends DataOrigin {
    override def isFrom(another: CliqueId): Boolean = false

    override def isFrom(cliqueId: CliqueId, brokerInfo: BrokerInfo): Boolean = false
  }

  sealed trait FromClique extends DataOrigin {
    def cliqueId: CliqueId
    def brokerInfo: BrokerInfo

    override def isFrom(another: CliqueId): Boolean = cliqueId == another

    override def isFrom(_cliqueId: CliqueId, _brokerInfo: BrokerInfo): Boolean =
      cliqueId == _cliqueId && _brokerInfo == brokerInfo
  }
  final case class InterClique(cliqueId: CliqueId, brokerInfo: BrokerInfo) extends FromClique
  final case class IntraClique(cliqueId: CliqueId, brokerInfo: BrokerInfo) extends FromClique

  def from(selfCliqueId: CliqueId,
           remoteCliqueId: CliqueId,
           remoteBrokerInfo: BrokerInfo): FromClique = {
    if (remoteCliqueId == selfCliqueId) IntraClique(remoteCliqueId, remoteBrokerInfo)
    else InterClique(remoteCliqueId, remoteBrokerInfo)
  }
}
