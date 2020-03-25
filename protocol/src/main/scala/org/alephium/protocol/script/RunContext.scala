package org.alephium.protocol.script

import akka.util.ByteString

import org.alephium.util.AVector

final case class RunContext(rawData: ByteString, instructions: AVector[Instruction])
