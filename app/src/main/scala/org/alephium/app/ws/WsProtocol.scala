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

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

import org.alephium.api.ApiModelCodec
import org.alephium.api.model.{BlockAndEvents, ContractEvent, TransactionTemplate}
import org.alephium.app.ws.WsParams.{WsCorrelationId, WsSubscriptionParams}
import org.alephium.app.ws.WsSubscriptionsState.{
  AddressKey,
  AddressWithEventIndexKey,
  ContractEventKey
}
import org.alephium.json.Json._
import org.alephium.protocol.Hash
import org.alephium.protocol.model.Address
import org.alephium.protocol.vm.LockupScript
import org.alephium.rpc.model.JsonRPC
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{AVector, EitherF, Hex}

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
    protected[ws] val BlockEvent: WsEventType = "block"
    protected[ws] val TxEvent: WsEventType    = "tx"

    protected[ws] val Block: SimpleSubscribeParams = SimpleSubscribeParams(BlockEvent)
    protected[ws] val Tx: SimpleSubscribeParams    = SimpleSubscribeParams(TxEvent)

    protected[ws] def read(json: ujson.Value): Either[Error, WsSubscriptionParams] = {
      json match {
        case ujson.Str(eventType) =>
          eventType match {
            case BlockEvent | TxEvent => Right(SimpleSubscribeParams(eventType))
            case _                    => Left(WsError.invalidParamsFormat(json))
          }
        case unsupported => Left(WsError.invalidParamsFormat(unsupported))
      }
    }
  }

  final case class ContractEventsSubscribeParams(
      addresses: AVector[Address.Contract],
      eventIndex: Option[WsEventIndex]
  ) extends WsSubscriptionParams {
    lazy val subscriptionId: WsSubscriptionId =
      Hash
        .hash(
          addresses
            .map(_.toBase58)
            .sorted
            .map(address => s"${eventIndex.getOrElse("*")}/$address")
            .mkString(",")
        )
    def toContractEventKeys: AVector[ContractEventKey] = eventIndex match {
      case Some(index) => addresses.map(addr => AddressWithEventIndexKey(addr.toBase58, index))
      case None        => addresses.map(addr => AddressKey(addr.toBase58))
    }
  }
  object ContractEventsSubscribeParams {
    protected[ws] val ContractEvent: WsEventType = "contract"
    protected[ws] val AddressesField             = "addresses"
    protected[ws] val EventIndexField            = "eventIndex"
    protected[ws] val LowestContractEventIndex   = -3

    protected[ws] def fromSingle(
        address: Address.Contract,
        eventIndex: Option[WsEventIndex]
    ): ContractEventsSubscribeParams =
      ContractEventsSubscribeParams(AVector(address), eventIndex)

    protected[ws] def buildUniqueContractAddresses(
        addressArr: mutable.ArrayBuffer[ujson.Value]
    ): Either[Error, AVector[Address.Contract]] = {
      EitherF
        .foldTry(addressArr, mutable.Set.empty[Address.Contract]) { case (addresses, addressVal) =>
          addressVal.strOpt match {
            case Some(address) =>
              LockupScript.p2c(address).map(Address.Contract(_)) match {
                case Some(contractAddress) if addresses.contains(contractAddress) =>
                  Left(WsError.duplicatedAddresses(address))
                case Some(contractAddress) =>
                  Right(addresses.addOne(contractAddress))
                case None => Left(WsError.invalidContractAddress(address))
              }
            case None => Left(WsError.invalidContractAddressType)
          }
        }
        .map(AVector.from)
    }

    protected[ws] def read(
        jsonObj: ujson.Obj,
        contractAddressLimit: Int
    ): Either[Error, ContractEventsSubscribeParams] = {
      def isValidEventIndex(eventIndex: Double) =
        eventIndex.isValidInt && eventIndex.toInt >= LowestContractEventIndex

      jsonObj.value.get(AddressesField) match {
        case Some(ujson.Arr(addressArr)) if addressArr.isEmpty =>
          Left(WsError.emptyContractAddress)
        case Some(ujson.Arr(addressArr)) if addressArr.length > contractAddressLimit =>
          Left(WsError.tooManyContractAddresses(contractAddressLimit))
        case Some(ujson.Arr(addressArr)) =>
          buildUniqueContractAddresses(addressArr)
            .flatMap { addresses =>
              jsonObj.value.get(EventIndexField) match {
                case Some(ujson.Num(eventIndex)) if isValidEventIndex(eventIndex) =>
                  Right(ContractEventsSubscribeParams(addresses, Option(eventIndex.toInt)))
                case None =>
                  Right(ContractEventsSubscribeParams(addresses, None))
                case Some(value) =>
                  Left(WsError.invalidContractParamsEventIndexType(value))
              }
            }
        case _ =>
          Left(WsError.invalidContractParamsFormat(jsonObj))
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

final case class WsRequest(id: WsCorrelationId, params: WsSubscriptionParams)
object WsRequest extends ApiModelCodec {
  import WsParams._

  implicit protected[ws] val wsRequestWriter: Writer[WsRequest] = writer[Request].comap[WsRequest] {
    req =>
      req.params match {
        case SimpleSubscribeParams(eventType) =>
          Request(WsMethod.SubscribeMethod, ujson.Arr(ujson.Str(eventType)), req.id)
        case UnsubscribeParams(subscriptionId) =>
          Request(
            WsMethod.UnsubscribeMethod,
            ujson.Arr(ujson.Str(subscriptionId.toHexString)),
            req.id
          )
        case ContractEventsSubscribeParams(addresses, eventIndexOpt) =>
          val addressArr =
            ujson.Arr.from(
              addresses.map(address => ujson.Str(address.toBase58))
            )
          val optionalEventIndexEntry =
            eventIndexOpt
              .map { eventIndex =>
                ContractEventsSubscribeParams.EventIndexField -> ujson.Num(eventIndex.toDouble)
              }

          Request(
            WsMethod.SubscribeMethod,
            ujson.Arr(
              ujson.Str(ContractEventsSubscribeParams.ContractEvent),
              ujson.Obj(
                ContractEventsSubscribeParams.AddressesField -> addressArr,
                optionalEventIndexEntry.toList*
              )
            ),
            req.id
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
        case ujson.Arr(arr) if arr.length == 2 =>
          arr(0) match {
            case ujson.Str(ContractEventsSubscribeParams.ContractEvent) =>
              arr(1) match {
                case ujson.Obj(filterObj) =>
                  ContractEventsSubscribeParams.read(filterObj, contractAddressLimit)
                case unsupported =>
                  Left(WsError.invalidParamsFormat(unsupported))
              }
            case unsupported =>
              Left(WsError.invalidParamsFormat(unsupported))
          }
        case unsupported =>
          Left(WsError.invalidParamsFormat(unsupported))
      }
    readParams.map(params => WsRequest(r.id, params))
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
    WsRequest(id, params)
  }

  protected[ws] def unsubscribe(
      id: WsCorrelationId,
      subscriptionId: WsSubscriptionId
  ): WsRequest = {
    WsRequest(id, UnsubscribeParams(subscriptionId))
  }
}
