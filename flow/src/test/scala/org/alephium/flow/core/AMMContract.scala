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

package org.alephium.flow.core

import org.alephium.protocol.vm.lang.Compiler

object AMMContract {
  lazy val swapContract =
    s"""
       |// Simple swap contract purely for testing
       |
       |TxContract Swap(tokenId: ByteVec, mut alphReserve: U256, mut tokenReserve: U256) {
       |  event AddLiquidity(lp: Address, attoAlphAmount: U256, tokenAmount: U256)
       |  event SwapToken(buyer: Address, attoAlphAmount: U256)
       |  event SwapAlph(buyer: Address, tokenAmount: U256)
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn addLiquidity(lp: Address, attoAlphAmount: U256, tokenAmount: U256) -> () {
       |    emit AddLiquidity(lp, attoAlphAmount, tokenAmount)
       |
       |    transferAlphToSelf!(lp, attoAlphAmount)
       |    transferTokenToSelf!(lp, tokenId, tokenAmount)
       |    alphReserve = alphReserve + attoAlphAmount
       |    tokenReserve = tokenReserve + tokenAmount
       |  }
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn swapToken(buyer: Address, attoAlphAmount: U256) -> () {
       |    emit SwapToken(buyer, attoAlphAmount)
       |
       |    let tokenAmount = tokenReserve - alphReserve * tokenReserve / (alphReserve + attoAlphAmount)
       |    transferAlphToSelf!(buyer, attoAlphAmount)
       |    transferTokenFromSelf!(buyer, tokenId, tokenAmount)
       |    alphReserve = alphReserve + attoAlphAmount
       |    tokenReserve = tokenReserve - tokenAmount
       |  }
       |
       |  @using(preapprovedAssets = true, assetsInContract = true)
       |  pub fn swapAlph(buyer: Address, tokenAmount: U256) -> () {
       |    emit SwapAlph(buyer, tokenAmount)
       |
       |    let attoAlphAmount = alphReserve - alphReserve * tokenReserve / (tokenReserve + tokenAmount)
       |    transferTokenToSelf!(buyer, tokenId, tokenAmount)
       |    transferAlphFromSelf!(buyer, attoAlphAmount)
       |    alphReserve = alphReserve - attoAlphAmount
       |    tokenReserve = tokenReserve + tokenAmount
       |  }
       |}
       |""".stripMargin
  lazy val swapCode = Compiler.compileContract(swapContract).toOption.get

  lazy val swapProxyContract: String =
    s"""
       |TxContract SwapProxy(swapContractId: ByteVec, tokenId: ByteVec) {
       |  @using(preapprovedAssets = true)
       |  pub fn addLiquidity(lp: Address, attoAlphAmount: U256, tokenAmount: U256) -> () {
       |    approveAlph!(lp, attoAlphAmount)
       |    approveToken!(lp, tokenId, tokenAmount)
       |    Swap(swapContractId).addLiquidity(lp, attoAlphAmount, tokenAmount)
       |  }
       |
       |  @using(preapprovedAssets = true)
       |  pub fn swapToken(buyer: Address, attoAlphAmount: U256) -> () {
       |    approveAlph!(buyer, attoAlphAmount)
       |    Swap(swapContractId).swapToken(buyer, attoAlphAmount)
       |  }
       |
       |  @using(preapprovedAssets = true)
       |  pub fn swapAlph(buyer: Address, tokenAmount: U256) -> () {
       |    approveToken!(buyer, tokenId, tokenAmount)
       |    Swap(swapContractId).swapAlph(buyer, tokenAmount)
       |  }
       |}
       |
       |$swapContract
       |""".stripMargin
  lazy val swapProxyCode = Compiler.compileContract(swapProxyContract).toOption.get
}
