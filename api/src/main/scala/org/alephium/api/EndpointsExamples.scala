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

import java.math.BigInteger
import java.net.{InetAddress, InetSocketAddress}

import sttp.tapir.EndpointIO.Example

import org.alephium.api.model._
import org.alephium.protocol._
import org.alephium.protocol.model
import org.alephium.protocol.model.{
  Address,
  BlockHash,
  CliqueId,
  ContractId,
  NetworkId,
  TokenId,
  TransactionId
}
import org.alephium.protocol.vm.{LockupScript, StatefulContract, UnlockScript}
import org.alephium.serde._
import org.alephium.util._
import org.alephium.util.Hex.HexStringSyntax

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
// scalastyle:off magic.number file.size.limit
trait EndpointsExamples extends ErrorExamples {

  private val networkId = NetworkId.AlephiumMainNet
  private val lockupScript =
    LockupScript.asset("1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n").get
  private val publicKey = PublicKey
    .from(Hex.unsafe("d1b70d2226308b46da297486adb6b4f1a8c1842cb159ac5ec04f384fe2d6f5da28"))
    .get
  private val unlockupScript: UnlockScript =
    UnlockScript.p2pkh(publicKey)
  private val unlockupScriptBytes      = serialize(unlockupScript)
  protected val defaultUtxosLimit: Int = 512
  val address                          = Address.Asset(lockupScript)
  val contractAddress = Address.Contract(
    LockupScript.p2c(
      ContractId.unsafe(
        Hash.unsafe(Hex.unsafe("109b05391a240a0d21671720f62fe39138aaca562676053900b348a51e11ba25"))
      )
    )
  )
  private val cliqueId          = CliqueId(publicKey)
  private val port              = 12344
  private val minerApiPort      = 12355
  private val wsPort            = 12366
  private val restPort          = 12377
  private val inetSocketAddress = new InetSocketAddress("1.2.3.4", port)
  private val inetAddress       = inetSocketAddress.getAddress
  private val peerAddress       = PeerAddress(inetAddress, restPort, wsPort, minerApiPort)
  private val peers             = AVector(peerAddress)
  private val bigAmount         = Amount(ALPH.oneAlph.mulUnsafe(U256.Two))
  private def alph(value: Int)  = Amount(ALPH.oneAlph.mulUnsafe(U256.unsafe(value)))
  private val height            = 42
  val balance                   = alph(10)
  val halfBalance               = alph(5)
  val signature = Signature
    .from(
      Hex.unsafe(
        "9e1a35b2931bd04e6780d01c36e3e5337941aa80f173cfe4f4e249c44ab135272b834c1a639db9c89d673a8a30524042b0469672ca845458a5a0cf2cad53221b"
      )
    )
    .get
  protected val hash =
    Hash.from(Hex.unsafe("798e9e137aec7c2d59d9655b4ffa640f301f628bf7c365083bb255f6aa5f89ef")).get
  private val blockHash = BlockHash
    .from(Hex.unsafe("bdaf9dc514ce7d34b6474b8ca10a3dfb93ba997cb9d5ff1ea724ebe2af48abe5"))
    .get
  val hexString    = "35d1b2a520a0da34c5eb8d712aa9cc"
  val byteString   = Hex.unsafe(hexString)
  protected val ts = TimeStamp.unsafe(1611041396892L)
  val txId =
    TransactionId.unsafe(
      Hash.from(Hex.unsafe("503bfb16230888af4924aa8f8250d7d348b862e267d75d3147f1998050b6da69")).get
    )
  val contractId =
    ContractId.unsafe(
      Hash.from(Hex.unsafe("1a21d30793fdf47bf07694017d0d721e94b78dffdc9c8e0b627833b66e5c75d8")).get
    )
  private val tokens = AVector(
    Token(TokenId.hash("token1"), alph(42).value),
    Token(TokenId.hash("token2"), alph(1000).value)
  )
  private val lockedTokens = AVector(Token(TokenId.hash("token3"), alph(65).value))

  val defaultDestinations = AVector(Destination(address, bigAmount, None, None))
  val moreSettingsDestinations = AVector(
    Destination(address, bigAmount, Some(tokens), Some(ts))
  )
  private val outputRef = OutputRef(hint = 23412, key = hash)

  private val inputAsset = AssetInput(
    outputRef,
    unlockupScriptBytes
  )

  private val outputAsset: FixedAssetOutput = FixedAssetOutput(
    1,
    hash,
    bigAmount,
    address,
    tokens,
    ts,
    hash.bytes
  )

  private val outputContract: Output = ContractOutput(
    1,
    hash,
    bigAmount,
    contractAddress,
    tokens
  )

  private val unsignedTx = UnsignedTx(
    txId,
    1,
    1,
    None,
    model.minimalGas.value,
    model.nonCoinbaseMinGasPrice.value,
    AVector(inputAsset),
    AVector(outputAsset)
  )

  private val transaction = Transaction(
    unsignedTx,
    true,
    AVector(outputRef),
    AVector(outputAsset.upCast(), outputContract),
    AVector(signature.bytes),
    AVector(signature.bytes)
  )

  private val transactionTemplate = TransactionTemplate(
    unsignedTx,
    AVector(signature.bytes),
    AVector(signature.bytes)
  )

  private val utxo = UTXO.from(
    outputRef,
    balance,
    tokens,
    ts,
    hash.bytes
  )

  private val ghostUncleBlockEntry = GhostUncleBlockEntry(blockHash, address)

  private val blockEntry = BlockEntry(
    blockHash,
    timestamp = ts,
    chainFrom = 1,
    chainTo = 2,
    height,
    deps = AVector(blockHash, blockHash),
    transactions = AVector(transaction),
    hash.bytes,
    1.toByte,
    hash,
    hash,
    hash.bytes,
    AVector(ghostUncleBlockEntry)
  )

  private val eventByBlockHash = ContractEventByBlockHash(
    txId,
    Address.contract(contractId),
    eventIndex = 1,
    fields = AVector(ValAddress(address), ValU256(U256.unsafe(10)))
  )

  private val blockAndEvents = BlockAndEvents(blockEntry, AVector(eventByBlockHash))

  private val blockCandidate = BlockCandidate(
    fromGroup = 1,
    toGroup = 0,
    headerBlob = Hex.unsafe("aaaa"),
    target = BigInteger.ONE.shiftLeft(18),
    txsBlob = Hex.unsafe("bbbbbbbbbb")
  )

  private val blockSolution = BlockSolution(
    blockBlob = Hex.unsafe("bbbbbbbb"),
    miningCount = U256.unsafe(1000)
  )

  private val blockHeaderEntry = BlockHeaderEntry(
    hash = blockHash,
    timestamp = ts,
    chainFrom = 1,
    chainTo = 2,
    height = height,
    deps = AVector(blockHash, blockHash)
  )

  private val event = ContractEvent(
    blockHash,
    txId,
    eventIndex = 1,
    fields = AVector(ValAddress(address), ValU256(U256.unsafe(10)))
  )

  private val eventByTxId = ContractEventByTxId(
    blockHash,
    Address.contract(contractId),
    eventIndex = 1,
    fields = AVector(ValAddress(address), ValU256(U256.unsafe(10)))
  )

  implicit val minerActionExamples: List[Example[MinerAction]] = List(
    Example[MinerAction](MinerAction.StartMining, Some("Start mining"), None),
    Example[MinerAction](MinerAction.StopMining, Some("Stop mining"), None)
  )

  implicit val mineOneBlockExamples: List[Example[(Group, Group)]] = List(
    Example[(Group, Group)]((Group(0), Group(1)), Some("Chain index"), None)
  )

  implicit val misbehaviorActionExamples: List[Example[MisbehaviorAction]] =
    List[Example[MisbehaviorAction]](
      moreSettingsExample(
        MisbehaviorAction.Unban(AVector(inetAddress)),
        "Unban"
      ),
      moreSettingsExample(
        MisbehaviorAction.Ban(AVector(inetAddress)),
        "Ban"
      )
    )

  implicit val discoveryActionExamples: List[Example[DiscoveryAction]] =
    List(
      Example[DiscoveryAction](
        DiscoveryAction.Unreachable(AVector(inetAddress)),
        None,
        Some("Set unreachable")
      ),
      Example[DiscoveryAction](
        DiscoveryAction.Reachable(AVector(inetAddress)),
        None,
        Some("Set reachable")
      )
    )

  implicit val nodeInfoExamples: List[Example[NodeInfo]] =
    simpleExample(
      NodeInfo(
        NodeInfo.BuildInfo(
          "1.2.3",
          "47c01136d52cdf29062f6a3598a36ebc1e4dc57e"
        ),
        true,
        Some(inetSocketAddress)
      )
    )

  implicit val nodeVersionExamples: List[Example[NodeVersion]] =
    simpleExample(
      NodeVersion(
        model.ReleaseVersion(0, 0, 1)
      )
    )

  implicit val getBlockHeaderEntryExample: List[Example[BlockHeaderEntry]] =
    simpleExample(blockHeaderEntry)

  // scalastyle:off line.size.limit
  implicit val getRawBlockExample: List[Example[RawBlock]] =
    simpleExample(
      RawBlock(value =
        hex"49c1050b901993b20000000000000000000000000000000000070000000000004386cda6c69b4d5aff405c10ae023d8311a90d53b337bc429fa00000000000018488701af02c77df4c7657243e3a321164f14b563c6bf6af821a0000000000023202720d0ff5ab292036c749039d90e90190bf2232b6a885a32f000000000002c856ac0bb2d363ffdca661fcb98e513cef3d3fe09f6888874a84000000000001465a437e5861962a8e23650c992429395499bf22a2e9954215d500000000000076e5076d72b10a3be4818e2ad88df4d97e43b50f1549216aa4760000000000033fde0a3e193492c1e9bb51b31718f72eb1fb94458d10369403775c91e90a5c32e6c4abe3216093814444e406f62995c247719a82476f6bc034b9508049bbd8a3f0f1d6862f0f8b218022ae0517394027dd03bf91c508fc565ab800000190edd91dee1b038d320100000080004e20bb9aca000001c4072325d841cdb7f30044dbe03c9b2c4c618d629bdcf6d5a58c596d70b83168d93e32f892774490fefa00000190efa2e16e000b010100000190edd91dee000100000000"
      )
    )
  // scalastyle:on line.size.limit

  implicit val chainParamsExamples: List[Example[ChainParams]] =
    simpleExample(
      ChainParams(
        networkId,
        numZerosAtLeastInHash = 18,
        groupNumPerBroker = 1,
        groups = 2
      )
    )

  implicit val selfCliqueExamples: List[Example[SelfClique]] =
    simpleExample(
      SelfClique(
        cliqueId,
        peers,
        selfReady = true,
        synced = true
      )
    )

  implicit val interCliquePeerInfosExamples: List[Example[AVector[InterCliquePeerInfo]]] =
    simpleExample(
      AVector(
        InterCliquePeerInfo(
          cliqueId,
          brokerId = 1,
          groupNumPerBroker = 1,
          inetSocketAddress,
          isSynced = true,
          clientVersion = "v1.0.0"
        )
      )
    )

  implicit val discoveredNeighborExamples: List[Example[AVector[model.BrokerInfo]]] =
    simpleExample(AVector(model.BrokerInfo.unsafe(cliqueId, 1, 1, inetSocketAddress)))

  implicit val misbehaviorsExamples: List[Example[AVector[PeerMisbehavior]]] =
    simpleExample(AVector(PeerMisbehavior(inetAddress, PeerStatus.Penalty(42))))

  implicit val unreachableBrokersExamples: List[Example[AVector[InetAddress]]] =
    simpleExample(AVector(inetAddress))

  implicit val unsignedTxExamples: List[Example[UnsignedTx]] = List(
    defaultExample(unsignedTx)
  )

  implicit val transactionExamples: List[Example[Transaction]] = List(
    defaultExample(transaction)
  )

  // scalastyle:off line.size.limit
  implicit val rawTransactionExamples: List[Example[RawTransaction]] = List(
    defaultExample(
      RawTransaction(
        hex"00000080004e20bb9aca000001c4072325d841cdb7f30044dbe03c9b2c4c618d629bdcf6d5a58c596d70b83168d93e32f892774490fefa00000190efa2e16e000b010100000190edd91dee000100000000"
      )
    )
  )
  // scalastyle:on line.size.limit

  implicit val hashrateResponseExamples: List[Example[HashRateResponse]] =
    simpleExample(HashRateResponse("100 MH/s"))

  implicit val currentDifficultyExamples: List[Example[CurrentDifficulty]] =
    simpleExample(CurrentDifficulty(new BigInteger("224000000000000")))

  implicit val blocksPerTimeStampRangeExamples: List[Example[BlocksPerTimeStampRange]] =
    simpleExample(BlocksPerTimeStampRange(AVector(AVector(blockEntry))))

  implicit val blocksAndEventsPerTimeStampRangeExamples
      : List[Example[BlocksAndEventsPerTimeStampRange]] =
    simpleExample(BlocksAndEventsPerTimeStampRange(AVector(AVector(blockAndEvents))))

  implicit val mempoolTransactionsExamples: List[Example[AVector[MempoolTransactions]]] =
    simpleExample(AVector(MempoolTransactions(0, 1, AVector(transactionTemplate))))

  implicit val blockEntryExamples: List[Example[BlockEntry]] =
    simpleExample(blockEntry)

  implicit val blockAndEventsExamples: List[Example[BlockAndEvents]] =
    simpleExample(blockAndEvents)

  implicit val blockEntryTemplateExamples: List[Example[BlockCandidate]] =
    simpleExample(blockCandidate)

  implicit val blockSolutionExamples: List[Example[BlockSolution]] =
    simpleExample(blockSolution)

  implicit val balanceExamples: List[Example[Balance]] = List(
    defaultExample(
      Balance(
        balance,
        balance.hint,
        halfBalance,
        halfBalance.hint,
        Some(tokens),
        Some(lockedTokens),
        utxoNum = 3
      )
    ),
    moreSettingsExample(
      Balance(
        balance,
        balance.hint,
        halfBalance,
        halfBalance.hint,
        Some(tokens),
        Some(lockedTokens),
        utxoNum = 3
      )
    )
  )

  implicit val utxosExamples: List[Example[UTXOs]] = List(
    defaultExample(UTXOs(AVector(utxo)))
  )

  implicit val groupExamples: List[Example[Group]] =
    simpleExample(Group(group = 2))

  implicit val hashesAtHeightExamples: List[Example[HashesAtHeight]] =
    simpleExample(HashesAtHeight(headers = AVector(blockHash, blockHash, blockHash)))

  implicit val chainInfoExamples: List[Example[ChainInfo]] =
    simpleExample(ChainInfo(currentHeight = height))

  implicit val buildTransactionExamples: List[Example[BuildTransaction]] = List(
    defaultExample(
      BuildTransaction(
        publicKey.bytes,
        None,
        defaultDestinations
      )
    ),
    moreSettingsExample(
      BuildTransaction(
        publicKey.bytes,
        Some(BuildTxCommon.BIP340Schnorr),
        moreSettingsDestinations,
        Some(AVector(outputRef)),
        Some(model.minimalGas),
        Some(model.nonCoinbaseMinGasPrice)
      )
    )
  )

  implicit val buildMultiAddressesTransactionExamples
      : List[Example[BuildMultiAddressesTransaction]] =
    List(
      defaultExample(
        BuildMultiAddressesTransaction(
          AVector(
            BuildMultiAddressesTransaction.Source(
              publicKey.bytes,
              defaultDestinations
            )
          ),
          None
        )
      )
    )

  implicit val buildSweepAddressTransactionExamples: List[Example[BuildSweepAddressTransactions]] =
    List(
      defaultExample(
        BuildSweepAddressTransactions(
          publicKey,
          address
        )
      ),
      moreSettingsExample(
        BuildSweepAddressTransactions(
          publicKey,
          address,
          Some(Amount(ALPH.oneAlph)),
          Some(ts),
          Some(model.minimalGas),
          Some(model.nonCoinbaseMinGasPrice)
        )
      )
    )

  implicit val buildTransactionResultExamples: List[Example[BuildTransactionResult]] =
    simpleExample(
      BuildTransactionResult(
        unsignedTx = hexString,
        model.minimalGas,
        model.nonCoinbaseMinGasPrice,
        txId,
        fromGroup = 2,
        toGroup = 1
      )
    )

  implicit val buildMultiGroupTransactionsResultsExamples
      : List[Example[AVector[BuildTransactionResult]]] =
    simpleExample(
      AVector(
        BuildTransactionResult(
          unsignedTx = hexString,
          model.minimalGas,
          model.nonCoinbaseMinGasPrice,
          txId,
          fromGroup = 2,
          toGroup = 1
        )
      )
    )

  implicit val buildSweepAddressTransactionsResultExamples
      : List[Example[BuildSweepAddressTransactionsResult]] = {
    val sweepAddressTxs = AVector(
      SweepAddressTransaction(
        txId,
        hexString,
        model.minimalGas,
        model.nonCoinbaseMinGasPrice
      )
    )
    simpleExample(BuildSweepAddressTransactionsResult(sweepAddressTxs, fromGroup = 2, toGroup = 1))
  }

  implicit val submitTransactionExamples: List[Example[SubmitTransaction]] =
    simpleExample(SubmitTransaction(unsignedTx = hexString, signature))

  implicit val buildMultisigAddressExample: List[Example[BuildMultisigAddress]] =
    simpleExample(
      BuildMultisigAddress(
        AVector(publicKey, publicKey),
        1
      )
    )

  implicit val buildMultisigAddressResultExample: List[Example[BuildMultisigAddressResult]] =
    simpleExample(
      BuildMultisigAddressResult(
        address
      )
    )

  implicit val buildMultisigTransactionExamples: List[Example[BuildMultisig]] = List(
    defaultExample(
      BuildMultisig(
        address,
        AVector(publicKey),
        defaultDestinations,
        None,
        None
      )
    ),
    moreSettingsExample(
      BuildMultisig(
        address,
        AVector(publicKey),
        moreSettingsDestinations,
        Some(model.minimalGas),
        Some(model.nonCoinbaseMinGasPrice)
      )
    )
  )

  implicit val buildSweepMultisigTransactionExamples: List[Example[BuildSweepMultisig]] = List(
    defaultExample(
      BuildSweepMultisig(
        address,
        AVector(publicKey),
        address,
        None,
        None,
        None,
        None,
        None,
        None
      )
    ),
    moreSettingsExample(
      BuildSweepMultisig(
        address,
        AVector(publicKey),
        address,
        Some(Amount(ALPH.oneAlph)),
        Some(ts),
        Some(model.minimalGas),
        Some(model.nonCoinbaseMinGasPrice),
        Some(10),
        Some(blockHash)
      )
    )
  )

  implicit val submitMultisigTransactionExamples: List[Example[SubmitMultisig]] =
    simpleExample(SubmitMultisig(unsignedTx = hexString, AVector(signature)))

  implicit val decodeTransactionExamples: List[Example[DecodeUnsignedTx]] =
    simpleExample(DecodeUnsignedTx(unsignedTx = hexString))

  implicit val decodeUnsignedTxExamples: List[Example[DecodeUnsignedTxResult]] =
    simpleExample(DecodeUnsignedTxResult(1, 2, unsignedTx))

  implicit val txResultExamples: List[Example[SubmitTxResult]] =
    simpleExample(SubmitTxResult(txId, fromGroup = 2, toGroup = 1))

  implicit val txStatusExamples: List[Example[TxStatus]] =
    List[Example[TxStatus]](
      Example(Confirmed(blockHash, 0, 1, 2, 3), None, None),
      Example(MemPooled(), None, Some("Tx is still in mempool")),
      Example(TxNotFound(), None, Some("Cannot find tx with the id"))
    )

  private val compilerOptions = CompilerOptions(ignoreUnusedConstantsWarnings = Some(true))

  implicit val compileScriptExamples: List[Example[Compile.Script]] =
    simpleExample(
      Compile.Script(
        code =
          s"TxScript Main { let token = Token(#36cdbfabca2d71622b6) token.withdraw(@${address.toBase58}, 1024) }",
        Some(compilerOptions)
      )
    )

  implicit val compileContractExamples: List[Example[Compile.Contract]] =
    simpleExample(
      Compile.Contract(
        // Note that we use this weird format to avoid Windows linebreak issue
        code =
          "Contract Foo(bar: ByteVec) {\n pub fn baz(amount: U256) -> () {\nissueToken!(amount)\n}}",
        Some(compilerOptions)
      )
    )

  implicit val compileProjectExamples: List[Example[Compile.Project]] =
    simpleExample(
      Compile.Project(
        code =
          "Contract Foo() {\n pub fn foo() -> () {}\n }\n TxScript Main(id: ByteVec) {\n Foo(id).foo() \n}",
        Some(compilerOptions)
      )
    )

  private val compileScriptResult = CompileScriptResult(
    version = "v0.0.1",
    name = "Main",
    bytecodeTemplate = hexString,
    bytecodeDebugPatch = CompileProjectResult.Patch("=1-1+ef"),
    fields = CompileResult.FieldsSig(
      names = AVector("aa", "bb", "cc", "dd", "ee"),
      types = AVector("Bool", "U256", "I256", "ByteVec", "Address"),
      isMutable = AVector(false, true, false, true, false)
    ),
    functions = AVector(
      CompileResult.FunctionSig(
        name = "bar",
        usePreapprovedAssets = false,
        useAssetsInContract = false,
        isPublic = true,
        paramNames = AVector("a", "b", "c", "d", "e"),
        paramTypes = AVector("Bool", "U256", "I256", "ByteVec", "Address"),
        paramIsMutable = AVector(false, true, false, true, false),
        returnTypes = AVector("U256", "I256", "ByteVec", "Address")
      )
    ),
    warnings = AVector("Found unused fields in Foo: a")
  )
  private val structSig = CompileResult.StructSig(
    name = "Foo",
    fieldNames = AVector("amount", "id"),
    fieldTypes = AVector("U256", "ByteVec"),
    isMutable = AVector(false, true)
  )
  implicit val compileScriptResultExamples: List[Example[CompileScriptResult]] =
    simpleExample(compileScriptResult)

  private val compileContractResult = CompileContractResult(
    version = "v0.0.1",
    name = "Foo",
    bytecode = hexString,
    bytecodeDebugPatch = CompileProjectResult.Patch("=1-1+ef"),
    codeHash = hash,
    codeHashDebug = hash,
    fields = CompileResult.FieldsSig(
      names = AVector("aa", "bb", "cc", "dd", "ee"),
      types = AVector("Bool", "U256", "I256", "ByteVec", "Address"),
      isMutable = AVector(false, true, false, true, false)
    ),
    functions = AVector(
      CompileResult.FunctionSig(
        name = "bar",
        usePreapprovedAssets = false,
        useAssetsInContract = false,
        isPublic = true,
        paramNames = AVector("a", "b", "c", "d", "e"),
        paramTypes = AVector("Bool", "U256", "I256", "ByteVec", "Address"),
        paramIsMutable = AVector(false, true, false, true, false),
        returnTypes = AVector("U256", "I256", "ByteVec", "Address")
      )
    ),
    maps = Some(CompileResult.MapsSig(AVector("foo"), AVector("Map[U256,U256]"))),
    constants = AVector(CompileResult.Constant("A", Val.True)),
    enums = AVector(
      CompileResult.Enum(
        name = "Color",
        fields = AVector(
          CompileResult.EnumField("Red", ValU256(U256.Zero)),
          CompileResult.EnumField("Blue", ValU256(U256.One))
        )
      )
    ),
    events = AVector(
      CompileResult.EventSig(
        name = "Bar",
        fieldNames = AVector("a", "b", "d", "e"),
        fieldTypes = AVector("Bool", "U256", "ByteVec", "Address")
      )
    ),
    warnings = AVector("Found unused fields in Foo: a"),
    stdInterfaceId = Some("0001")
  )
  implicit val compileContractResultExamples: List[Example[CompileContractResult]] =
    simpleExample(compileContractResult)

  implicit val compileProjectResultExamples: List[Example[CompileProjectResult]] =
    simpleExample(
      CompileProjectResult(
        contracts = AVector(compileContractResult),
        scripts = AVector(compileScriptResult),
        structs = Some(AVector(structSig))
      )
    )

  implicit val buildDeployContractTxExamples: List[Example[BuildDeployContractTx]] = List(
    defaultExample(BuildDeployContractTx(publicKey.bytes, None, bytecode = byteString)),
    moreSettingsExample(
      BuildDeployContractTx(
        publicKey.bytes,
        Some(BuildTxCommon.BIP340Schnorr),
        byteString,
        Some(bigAmount),
        Some(tokens),
        Some(bigAmount),
        Some(address),
        Some(model.minimalGas),
        Some(model.nonCoinbaseMinGasPrice)
      )
    )
  )

  implicit val buildExecuteScriptTxExamples: List[Example[BuildExecuteScriptTx]] = List(
    defaultExample(BuildExecuteScriptTx(publicKey.bytes, None, bytecode = byteString)),
    moreSettingsExample(
      BuildExecuteScriptTx(
        publicKey.bytes,
        Some(BuildTxCommon.Default),
        byteString,
        Some(Amount(model.dustUtxoAmount)),
        Some(tokens),
        Some(model.minimalGas),
        Some(model.nonCoinbaseMinGasPrice)
      )
    )
  )

  implicit val buildDeployContractTxResultExamples: List[Example[BuildDeployContractTxResult]] =
    simpleExample(
      BuildDeployContractTxResult(
        fromGroup = 2,
        toGroup = 2,
        unsignedTx = hexString,
        model.minimalGas,
        model.nonCoinbaseMinGasPrice,
        txId = txId,
        contractAddress = Address.contract(contractId)
      )
    )

  implicit val buildExecuteScriptTxResultExamples: List[Example[BuildExecuteScriptTxResult]] =
    simpleExample(
      BuildExecuteScriptTxResult(
        fromGroup = 2,
        toGroup = 2,
        unsignedTx = hexString,
        model.minimalGas,
        model.nonCoinbaseMinGasPrice,
        txId = txId
      )
    )

  implicit lazy val contractStateExamples: List[Example[ContractState]] =
    simpleExample(existingContract)

  private def asset(n: Long) = AssetState.from(
    ALPH.alph(n),
    AVector(Token(id = TokenId.hash(s"token${n}"), amount = ALPH.nanoAlph(n)))
  )
  private val anotherContractId = ContractId.hash("contract")
  private val code              = StatefulContract.forSMT.toContract().toOption.get
  private lazy val existingContract = ContractState(
    address = Address.contract(anotherContractId),
    bytecode = code,
    codeHash = code.hash,
    initialStateHash = Some(code.initialStateHash(AVector.empty, AVector.empty)),
    immFields = AVector[Val](ValU256(ALPH.alph(1))),
    mutFields = AVector[Val](ValU256(ALPH.alph(2))),
    asset = asset(2)
  )
  implicit val testContractExamples: List[Example[TestContract]] = {
    simpleExample(
      TestContract(
        group = Some(0),
        address = Some(Address.contract(ContractId.zero)),
        bytecode = code,
        initialImmFields = Some(AVector[Val](ValU256(ALPH.alph(1)))),
        initialMutFields = Some(AVector[Val](ValU256(ALPH.alph(2)))),
        initialAsset = Some(asset(1)),
        methodIndex = Some(0),
        args = Some(AVector[Val](ValU256(ALPH.oneAlph))),
        existingContracts = Some(AVector(existingContract)),
        inputAssets = Some(AVector(TestInputAsset(address, asset(3))))
      )
    )
  }

  implicit val testContractResultExamples: List[Example[TestContractResult]] =
    simpleExample(
      TestContractResult(
        address = contractAddress,
        codeHash = hash,
        returns = AVector[Val](ValU256(ALPH.oneAlph)),
        gasUsed = 20000,
        contracts = AVector(existingContract),
        txInputs = AVector(contractAddress),
        txOutputs =
          AVector(ContractOutput(1234, hash, Amount(ALPH.oneAlph), contractAddress, tokens)),
        events = AVector(eventByTxId),
        debugMessages = AVector(DebugMessage(contractAddress, "Debugging!"))
      )
    )

  val callContractExample = CallContract(
    group = 0,
    worldStateBlockHash = Some(blockHash),
    txId = Some(txId),
    address = Address.contract(ContractId.zero),
    methodIndex = 0,
    args = Some(AVector[Val](ValU256(U256.Zero))),
    interestedContracts = Some(AVector(contractAddress)),
    inputAssets = Some(AVector(TestInputAsset(address, asset(3))))
  )
  implicit val callContractExamples: List[Example[CallContract]] = {
    simpleExample(callContractExample)
  }

  val callContractResultExample: CallContractResult = CallContractSucceeded(
    returns = AVector[Val](ValU256(U256.Zero)),
    gasUsed = 20000,
    contracts = AVector(existingContract),
    txInputs = AVector(contractAddress),
    txOutputs = AVector(ContractOutput(1, hash, Amount(ALPH.oneAlph), contractAddress, tokens)),
    events = AVector(eventByTxId),
    debugMessages = AVector(DebugMessage(contractAddress, "Debugging!"))
  )
  implicit val callContractResultExamples: List[Example[CallContractResult]] = {
    simpleExample(callContractResultExample)
  }

  implicit val multipleCallContractExamples: List[Example[MultipleCallContract]] = {
    simpleExample(MultipleCallContract(AVector(callContractExample)))
  }

  implicit val multipleCallContractResultExamples: List[Example[MultipleCallContractResult]] = {
    simpleExample(MultipleCallContractResult(AVector(callContractResultExample)))
  }

  implicit val callTxScriptExamples: List[Example[CallTxScript]] = {
    simpleExample(CallTxScript(group = 0, bytecode = Hex.unsafe("0011")))
  }

  implicit val callTxScriptResultExamples: List[Example[CallTxScriptResult]] = {
    simpleExample(
      CallTxScriptResult(
        returns = AVector[Val](ValU256(U256.One), ValBool(false)),
        gasUsed = 100000,
        contracts = AVector(existingContract),
        txInputs = AVector(contractAddress),
        txOutputs = AVector(ContractOutput(1, hash, Amount(ALPH.oneAlph), contractAddress, tokens)),
        events = AVector.empty,
        debugMessages = AVector.empty
      )
    )
  }

  implicit val exportFileExamples: List[Example[ExportFile]] =
    simpleExample(ExportFile("exported-blocks-file"))

  implicit val addressExamples: List[Example[Address.Asset]] =
    simpleExample(address)

  implicit val minerAddressesExamples: List[Example[MinerAddresses]] =
    simpleExample(MinerAddresses(AVector(address)))

  implicit val booleanExamples: List[Example[Boolean]] =
    simpleExample(true)

  implicit val intExamples: List[Example[Int]] =
    simpleExample(100)

  implicit val verifySignatureExamples: List[Example[VerifySignature]] =
    simpleExample(VerifySignature(Hex.unsafe(hexString), signature, publicKey))

  implicit val targetToHashrateExamples: List[Example[TargetToHashrate]] =
    simpleExample(TargetToHashrate(target = model.Target.unsafe(Hex.unsafe("1b032b55")).bits))

  implicit val targetToHashrateResultExamples: List[Example[TargetToHashrate.Result]] =
    simpleExample(TargetToHashrate.Result(new BigInteger("355255758493400")))

  implicit val eventsExamples: List[Example[ContractEvents]] =
    simpleExample(ContractEvents(events = AVector(event), 2))

  implicit val eventsByTxIdExamples: List[Example[ContractEventsByTxId]] =
    simpleExample(ContractEventsByTxId(events = AVector(eventByTxId)))

  implicit val eventsByBlockHashExamples: List[Example[ContractEventsByBlockHash]] =
    simpleExample(ContractEventsByBlockHash(events = AVector(eventByBlockHash)))

  implicit val eventsVectorExamples: List[Example[AVector[ContractEvents]]] =
    simpleExample(AVector(ContractEvents(events = AVector(event), 3)))

  implicit val contractParentExamples: List[Example[ContractParent]] =
    simpleExample(ContractParent(Option(contractAddress)))

  implicit val subContractsExamples: List[Example[SubContracts]] =
    simpleExample(SubContracts(subContracts = AVector(contractAddress), nextStart = 10))

  implicit val outputRefExamples: List[Example[OutputRef]] = simpleExample(outputRef)

  implicit val transactionIdExamples: List[Example[TransactionId]] =
    simpleExample(transaction.unsigned.txId)
}
// scalastyle:on magic.number
