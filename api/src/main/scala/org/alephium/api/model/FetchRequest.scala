package org.alephium.api.model

import org.alephium.util.TimeStamp

 final case class FetchRequest(fromTs: TimeStamp, toTs: TimeStamp)

