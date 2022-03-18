// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.api

import org.alephium.protocol._
import org.alephium.protocol.config._
import org.alephium.protocol.model._
import org.alephium.protocol.vm
import org.alephium.protocol.vm.LockupScript
import org.alephium.util._

trait ApiModelFixture
    extends ModelGenerators
    with ConsensusConfigFixture.Default
    with NetworkConfigFixture.Default
    with CompilerConfigFixture.Default
    with ApiModelCodec {

  val instrs: AVector[vm.Instr[vm.StatefulContext]] =
    AVector(vm.ConstTrue, vm.ConstFalse, vm.I256Const3)
  val method  = vm.Method[vm.StatefulContext](true, true, 1, 2, 3, instrs)
  val methods = AVector(method, method)
  val script  = vm.StatefulScript.unsafe(methods)
  val assetTxOutputRef = AssetOutputRef.unsafe(
    Hint.unsafe(0),
    hashGen.sample.get
  )
  val contractTxOutputRef = ContractOutputRef.unsafe(
    Hint.unsafe(0),
    hashGen.sample.get
  )
  val (priKey, pubKey) = keypairGen.sample.get

  val sigature = SignatureSchema.sign(hashGen.sample.get.bytes, priKey)

  val scriptPair = p2pkScriptGen(GroupIndex.unsafe(0)).sample.get

  val txInput = TxInput(assetTxOutputRef, scriptPair.unlock)
  val assetOutput = TxOutput.asset(
    ALPH.oneAlph,
    AVector.empty,
    LockupScript.p2pkh(Hash.zero)
  )

  val contractOutput = TxOutput.contract(
    ALPH.oneAlph,
    LockupScript.p2c(Hash.zero)
  )

  val unsignedTransaction = UnsignedTransaction(
    Some(script),
    defaultGas,
    defaultGasPrice,
    AVector(txInput),
    AVector(assetOutput)
  )

  val transaction = Transaction(
    unsignedTransaction,
    scriptExecutionOk = true,
    AVector(contractTxOutputRef),
    AVector(assetOutput, contractOutput),
    AVector(sigature),
    AVector(sigature)
  )

  val transactionTemplate = TransactionTemplate(
    unsignedTransaction,
    AVector(sigature),
    AVector(sigature)
  )
}
