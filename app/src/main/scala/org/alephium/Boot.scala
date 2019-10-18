package org.alephium

import org.alephium.appserver.Server
import org.alephium.flow.platform.Mode

object Boot extends Server(new Mode.Local) with App
