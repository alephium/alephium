package org.alephium.rpc

import org.alephium.util.{AlephiumSpec, AVector}

class AVectorJsonSpec extends AlephiumSpec {
  import AVectorJson._

  "AVectorJson" should "encode and decode" in {
    forAll { ys: List[Int] =>
      val xs   = AVector.from(ys)
      val json = encodeAVector[Int].apply(xs)
      decodeAVector[Int].apply(json.hcursor).right.get is xs
    }
  }
}
