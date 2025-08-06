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

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import akka.util.ByteString
import org.scalatest.{Assertion, EitherValues}

import org.alephium.api.model._
import org.alephium.json.Json._
import org.alephium.protocol.{model => protocol, ALPH}
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.vm
import org.alephium.serde.deserialize
import org.alephium.util._

class ProtocolConversionSpec extends AlephiumSpec with EitherValues with NumericHelpers {
  it should "convert Script" in new Fixture {
    checkData[Script, vm.StatefulScript](script, Script.fromProtocol, _.toProtocol().rightValue)
  }

  it should "convert TxOutputRef" in new Fixture {
    checkData[OutputRef, protocol.TxOutputRef](
      assetTxOutputRef,
      OutputRef.from,
      _.unsafeToAssetOutputRef()
    )
  }

  it should "convert TxInput" in new Fixture {
    checkData[AssetInput, protocol.TxInput](
      txInput,
      AssetInput.fromProtocol,
      _.toProtocol().rightValue
    )
  }

  it should "convert FixedAssetOutput" in new Fixture {
    checkData[FixedAssetOutput, protocol.AssetOutput](
      assetOutput,
      out => FixedAssetOutput.fromProtocol(out, protocol.TransactionId.random, 0),
      _.toProtocol()
    )
  }

  it should "convert UnsignedTransaction" in new Fixture {
    checkData[UnsignedTx, protocol.UnsignedTransaction](
      unsignedTransaction,
      UnsignedTx.fromProtocol,
      _.toProtocol().rightValue
    )

    UnsignedTx
      .fromProtocol(unsignedTransaction)
      .copy(txId = protocol.TransactionId.random)
      .toProtocol()
      .leftValue is "Invalid hash"
  }

  it should "convert Transaction" in new Fixture {
    checkData[Transaction, protocol.Transaction](
      transaction,
      Transaction.fromProtocol,
      _.toProtocol().rightValue
    )
  }

  it should "convert TransactionTemplate" in new Fixture {
    val txSeenAt = TimeStamp.now()
    checkData[TransactionTemplate, (protocol.TransactionTemplate, TimeStamp)](
      (transactionTemplate, txSeenAt),
      { case (template, ts) => TransactionTemplate.fromProtocol(template, ts) },
      template => (template.toProtocol().rightValue, template.seenAt)
    )
  }

  it should "convert serialized Transaction spnapshot" in new Fixture {
    val dir = new File("../protocol/src/test/resources/models/transaction")

    dir.listFiles.filter(_.getName().endsWith(".serialized.txt")).foreach { file =>
      val content = readFile(file)
      val tx      = deserialize[protocol.Transaction](content).value

      checkData[Transaction, protocol.Transaction](
        tx,
        Transaction.fromProtocol,
        _.toProtocol().rightValue
      )
    }
  }

  it should "convert serialized Block spnapshot" in new Fixture {
    val dir = new File("../protocol/src/test/resources/models/block")

    dir.listFiles.filter(_.getName().endsWith(".serialized.txt")).foreach { file =>
      val content = readFile(file)
      val block   = deserialize[protocol.Block](content).value

      checkData[BlockEntry, protocol.Block](
        block,
        BlockEntry
          .from(_, 0)(NetworkConfigFixture.PreRhone) // height not needed for protocol
          .rightValue,
        _.toProtocol().rightValue
      )
    }
  }

  it should "convert genesis block" in new Fixture {
    val block = blockGen(chainIndexGen.sample.value).sample.value
    val genesis = block
      .copy(header = block.header.copy(timestamp = ALPH.GenesisTimestamp))
      .copy(transactions = AVector.empty)

    genesis.isGenesis is true
    genesis.transactions.isEmpty is true
    BlockEntry.from(genesis, 0).isRight is true
  }

  trait Fixture extends ApiModelFixture {
    def checkData[T: Reader: Writer, P](
        protocol: P,
        convertToApi: P => T,
        convertToProtocol: T => P
    ): Assertion = {
      convertToProtocol(read[T](write(convertToApi(protocol)))) is protocol
    }

    def readFile(file: File): ByteString = {
      Hex
        .from(
          (new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            .filterNot(_.isWhitespace)
        )
        .value
    }

  }
}
