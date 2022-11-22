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

import org.scalacheck.Arbitrary.arbitrary

import org.alephium.api.OpenAPIGen.openAPIGen
import org.alephium.api.OpenAPIWriters.openApiJson
import org.alephium.util.AlephiumSpec

class OpenApiWritersSpec extends AlephiumSpec  with OpenApiGen{

  it should "write json without error" in {
    //forAll(openAPIGen,arbitrary[Boolean]) { case (openAPI,dropAuth)=>
    val openAPI = openAPIGen.sample.get
    println(s"${Console.RED}${Console.BOLD}*** openAPI ***\n\t${Console.RESET}${openAPI}")
    val dropAuth = arbitrary[Boolean].sample.get
    println(s"${Console.RED}${Console.BOLD}*** dropAuth ***\n\t${Console.RESET}${dropAuth}")

      val json = openApiJson(openAPI, dropAuth)
      println(s"${Console.RED}${Console.BOLD}*** json ***\n\t${Console.RESET}${json}")

      true is true
      //}
  }
}

