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

package org.alephium.app.ws

import scala.util.{Failure, Success, Try}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{BlockAndEvents, ContractEvent, TransactionTemplate}
import org.alephium.app.ws.WsParams.WsSubscriptionParams
import org.alephium.app.ws.WsRequest.Correlation
import org.alephium.app.ws.WsSubscriptionHandler.AddressWithIndex
import org.alephium.json.Json._
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AVector, Hex}

protected[ws] object WsMethod {
  private type WsMethodType = String
  val SubscribeMethod: WsMethodType    = "subscribe"
  val UnsubscribeMethod: WsMethodType  = "unsubscribe"
  val SubscriptionMethod: WsMethodType = "subscription"
}

protected[ws] object WsParams {
  protected[ws] type WsEventType      = String
  protected[ws] type WsEventIndex     = Int
  protected[ws] type WsId             = String
  protected[ws] type WsCorrelationId  = Long
  protected[ws] type WsSubscriptionId = Hash

  sealed trait WsParams
  sealed trait WsSubscriptionParams extends WsParams {
    def subscriptionId: WsSubscriptionId
  }
  final case class SimpleSubscribeParams(eventType: WsEventType) extends WsSubscriptionParams {
    lazy val subscriptionId: WsSubscriptionId = Hash.hash(eventType)
  }
  object SimpleSubscribeParams {
    private val BlockEvent: WsEventType = "block"
    private val TxEvent: WsEventType    = "tx"

    protected[ws] val eventTypes: AVector[WsEventType] = AVector(BlockEvent, TxEvent)

    protected[ws] val Block: SimpleSubscribeParams = SimpleSubscribeParams(BlockEvent)
    protected[ws] val Tx: SimpleSubscribeParams    = SimpleSubscribeParams(TxEvent)

    protected[ws] def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(eventTypeOrSubscriptionId) =>
          eventTypeOrSubscriptionId match {
            case BlockEvent | TxEvent => Right(SimpleSubscribeParams(eventTypeOrSubscriptionId))
            case unknown              => Left(WsError.invalidEventType(unknown))
          }
        case unsupported => Left(WsError.invalidSubscriptionEventTypes(unsupported))
      }
    }
  }

  final case class ContractEventsSubscribeParams(
      eventType: WsEventType,
      eventIndex: WsEventIndex,
      addresses: AVector[Address.Contract]
  ) extends WsSubscriptionParams {
    lazy val subscriptionId: WsSubscriptionId =
      Hash
        .hash(
          addresses
            .map(address => s"$eventType/$eventIndex/$address")
            .mkString(",")
        )
    def toAddressesWithIndex: AVector[AddressWithIndex] =
      addresses.map(addr => AddressWithIndex(addr.toBase58, eventIndex))
  }
  object ContractEventsSubscribeParams {
    protected[ws] val ContractEvent: WsEventType = "contract"

    protected[ws] def from(
        eventIndex: WsEventIndex,
        addresses: AVector[Address.Contract]
    ): ContractEventsSubscribeParams =
      ContractEventsSubscribeParams(ContractEvent, eventIndex, addresses)

    protected[ws] def fromSingle(
        eventIndex: WsEventIndex,
        address: Address.Contract
    ): ContractEventsSubscribeParams =
      ContractEventsSubscribeParams(ContractEvent, eventIndex, AVector(address))

    protected[ws] def read(
        jsonArr: ujson.Arr,
        contractAddressLimit: Int
    ): Either[Error, ContractEventsSubscribeParams] = {
      assume(jsonArr.arr.length == 3)
      (jsonArr(0), jsonArr(1), jsonArr(2)) match {
        case (ujson.Str(eventType), ujson.Num(eventIndex), ujson.Arr(addressArr)) =>
          eventType match {
            case ContractEvent if addressArr.isEmpty =>
              Left(WsError.emptyContractAddress)
            case ContractEvent if addressArr.length > contractAddressLimit =>
              Left(WsError.tooManyContractAddresses(contractAddressLimit))
            case ContractEvent if addressArr(0).strOpt.isEmpty =>
              Left(WsError.invalidContractAddressType)
            case ContractEvent =>
              WsUtils
                .buildUniqueContractAddresses(addressArr)
                .map(ContractEventsSubscribeParams.from(eventIndex.toInt, _))
            case unknown =>
              Left(WsError.invalidContractEventParams(unknown))
          }
        case unsupported => Left(WsError.invalidParamsArrayElements(unsupported))
      }
    }
  }

  final case class UnsubscribeParams(subscriptionId: WsSubscriptionId) extends WsSubscriptionParams
  object UnsubscribeParams {
    protected[ws] def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(subscriptionIdHex) =>
          (for {
            subscriptionIdHex <- Hex.from(subscriptionIdHex)
            subscriptionId    <- Hash.from(subscriptionIdHex)
          } yield subscriptionId) match {
            case None =>
              Left(WsError.invalidSubscriptionId(subscriptionIdHex))
            case Some(hash) =>
              Right(UnsubscribeParams(hash))
          }
        case unsupported =>
          Left(WsError.invalidUnsubscriptionFormat(unsupported))
      }
    }
  }

  sealed trait WsNotificationParams extends WsParams {
    def subscription: WsSubscriptionId
    protected[ws] def asJsonRpcNotification: ujson.Obj = {
      ujson
        .Obj(
          "method" -> WsMethod.SubscriptionMethod,
          "params" -> writeJs(this)
        )
    }
  }
  object WsNotificationParams extends ApiModelCodec {
    implicit val wsSubscriptionParamsWriter: Writer[WsNotificationParams] = {
      implicit val blockSubscriptionWriter: Writer[WsBlockNotificationParams]            = macroW
      implicit val txSubscriptionWriter: Writer[WsTxNotificationParams]                  = macroW
      implicit val contractSubscriptionWriter: Writer[WsContractEventNotificationParams] = macroW

      writer[ujson.Value].comap[WsNotificationParams] {
        case block: WsBlockNotificationParams            => writeJs(block)
        case tx: WsTxNotificationParams                  => writeJs(tx)
        case contract: WsContractEventNotificationParams => writeJs(contract)
      }
    }
  }

  final case class WsBlockNotificationParams(
      subscription: WsSubscriptionId,
      result: BlockAndEvents
  ) extends WsNotificationParams
  object WsBlockNotificationParams {
    protected[ws] def from(blockAndEvents: BlockAndEvents): WsBlockNotificationParams =
      WsBlockNotificationParams(
        SimpleSubscribeParams.Block.subscriptionId,
        blockAndEvents
      )
  }

  final case class WsTxNotificationParams(
      subscription: WsSubscriptionId,
      result: TransactionTemplate
  ) extends WsNotificationParams
  object WsTxNotificationParams {
    protected[ws] def from(txTemplate: TransactionTemplate): WsTxNotificationParams =
      WsTxNotificationParams(
        SimpleSubscribeParams.Tx.subscriptionId,
        txTemplate
      )
  }
  final case class WsContractEventNotificationParams(
      subscription: WsSubscriptionId,
      result: ContractEvent
  ) extends WsNotificationParams
}

final case class WsRequest(id: Correlation, params: WsSubscriptionParams)
object WsRequest extends ApiModelCodec {
  import WsParams._

  final case class Correlation(id: WsCorrelationId) extends WithId

  implicit protected[ws] val wsRequestWriter: Writer[WsRequest] = writer[Request].comap[WsRequest] {
    req =>
      req.params match {
        case SimpleSubscribeParams(eventType) =>
          Request(WsMethod.SubscribeMethod, ujson.Arr(ujson.Str(eventType)), req.id.id)
        case UnsubscribeParams(subscriptionId) =>
          Request(
            WsMethod.UnsubscribeMethod,
            ujson.Arr(ujson.Str(subscriptionId.toHexString)),
            req.id.id
          )
        case ContractEventsSubscribeParams(eventType, eventIndex, addresses) =>
          Request(
            WsMethod.SubscribeMethod,
            ujson.Arr(
              ujson.Str(eventType),
              ujson.Num(eventIndex.toDouble),
              ujson.Arr.from(addresses.map(address => ujson.Str(address.toBase58)))
            ),
            req.id.id
          )
      }
  }

  protected[ws] def fromJsonRpc(
      r: RequestUnsafe,
      contractAddressLimit: Int
  ): Either[Error, WsRequest] = {
    def readParams =
      r.params match {
        case ujson.Arr(arr) if arr.length == 1 =>
          r.method match {
            case WsMethod.SubscribeMethod   => SimpleSubscribeParams.read(arr(0))
            case WsMethod.UnsubscribeMethod => UnsubscribeParams.read(arr(0))
          }
        case ujson.Arr(arr) if arr.length == 3 =>
          ContractEventsSubscribeParams.read(arr, contractAddressLimit)
        case unsupported =>
          Left(WsError.invalidParamsFormat(unsupported))
      }
    readParams.map(params => WsRequest(Correlation(r.id), params))
  }

  protected[ws] def fromJsonString(
      msg: String,
      contractAddressLimit: Int
  ): Either[JsonRPC.Response.Failure, WsRequest] = {
    Try(read[RequestUnsafe](msg)) match {
      case Success(r) =>
        fromJsonRpc(r, contractAddressLimit).left.map(JsonRPC.Response.Failure(_, Option(r.id)))
      case Failure(ex) =>
        Left(JsonRPC.Response.Failure(Error(Error.ParseErrorCode, ex.getMessage), id = None))
    }
  }

  protected[ws] def subscribe(id: WsCorrelationId, params: WsSubscriptionParams): WsRequest = {
    WsRequest(Correlation(id), params)
  }

  protected[ws] def unsubscribe(
      id: WsCorrelationId,
      subscriptionId: WsSubscriptionId
  ): WsRequest = {
    WsRequest(Correlation(id), UnsubscribeParams(subscriptionId))
  }
}
