//package org.alephium.protocol.vm.lang
//
//import fastparse._
//import fastparse.SingleLineWhitespace._
//
//import org.alephium.protocol.vm
//import org.alephium.protocol.vm.{Instr, InstrCompanion, StatelessContext, Val}
//
//object OldParser {
//  private var variableCount = 0
//  private val variableCache = scala.collection.mutable.Map.empty[String, Int]
//  private[lang] def resetState(
//      ): Unit = {
//    variableCount = 0
//    variableCache.clear()
//  }
//
//  private[lang] def nums[_: P]: P[String]      = P(CharIn("0-9").rep(1)).!
//  private[lang] def instrName[_: P]: P[String] = P(CharIn("A-Z") ~~/ CharsWhileIn("a-zA-Z0-9", 0)).!
//
//  private[lang] def globalV[_: P]: P[String] =
//    P("#" ~~/ CharIn("a-zA-Z_") ~~/ CharsWhileIn("a-zA-Z0-9_", 0)).!
//  private[lang] def localV[_: P]: P[String] =
//    P("$" ~~/ CharIn("a-zA-Z_") ~~/ CharsWhileIn("a-zA-Z0-9_", 0)).!
//
//  private[lang] def lineSep[_: P]: P[Unit] = P("\n").rep(1)
//  private[lang] def end[_: P]: P[Unit]     = P(lineSep.rep ~ End)
//
//  private[lang] def getSimpleName(obj: Object): String = {
//    obj.getClass.getSimpleName.dropRight(1)
//  }
//
//  val types: Map[String, Val.Type] =
//    Val.Type.types.map(tpe => (getSimpleName(tpe), tpe)).toArray.toMap
//  private[lang] def tpe[_: P]: P[Val.Type] = instrName.filter(types.contains).map(types.apply)
//
//  private[lang] def validateVariable(token: String): Boolean = {
//    if (token(0) == '#') variableCache.contains(token) else true
//  }
//  private[lang] def convertVariable(token: String): String = {
//    if (token(0) == '#') variableCache(token).toString else token
//  }
//
//  val statelessInstrs: Map[String, InstrCompanion[StatelessContext]] =
//    vm.Instr.statelessInstrs.map(obj => (getSimpleName(obj), obj)).toArray.toMap
//  private[lang] def statelessInstr[_: P]: P[Instr[StatelessContext]] =
//    P(instrName ~ (nums | globalV).rep)
//      .filter(p => p._2.forall(validateVariable))
//      .map(p => (p._1, p._2.map(convertVariable)))
//      .map(p => statelessInstrs.get(p._1).map(_.parse(p._2)))
//      .filter(p => p.nonEmpty && p.get.isRight)
//      .map(_.get.toOption.get)
//
//  //  val statefulInstrs: Map[String, InstrCompanion[StatefulContext]] =
//  //    vm.Instr.statefulInstrs.map(obj => (getSimpleName(obj), obj)).toArray.toMap
//  //  private[asm] def statefulInstr[_: P]: P[Instr[StatefulContext]] =
//  //    P(id ~ id.rep ~ newline)
//  //      .map(p => statefulInstrs.get(p._1).map(_.parse(p._2)))
//  //      .filter(p => p.nonEmpty && p.get.isRight)
//  //      .map(_.get.toOption.get)
//
//  private[lang] def returnTypes[_: P]: P[Seq[Val.Type]] =
//    P("(" ~/ tpe.rep(min = 2, sep = ",") ~ ")") | tpe.?.map(_.toSeq)
//
//  // format: off
//  private[lang] def statelessMethod[_: P]: P[StatelessMethod] =
//    P("def" ~/ "(" ~ tpe.rep(0, ",") ~ ")" ~ ":" ~ returnTypes ~ lineSep
//      ~ statelessInstr.rep(1, sep=lineSep))
//      .map(StatelessMethod.tupled)
//  // format: on
//
//  private[lang] def field[_: P]: P[Field] =
//    P(globalV ~ ":" ~ tpe).map(Field.tupled).map { field =>
//      variableCache += field.id -> variableCount
//      variableCount += 1
//      field
//    }
//
//  // format: off
//  private[lang] def statelessScript[_: P]: P[StatelessScript] =
//    P(lineSep ~ field.rep(max = 0xFF, sep = lineSep) ~ lineSep
//        ~ statelessMethod.rep(1, sep = lineSep) ~ end)
//      .map(StatelessScript.tupled)
//  // format: on
//
//  def parseStateless(input: String): Parsed[StatelessScript] = {
//    resetState()
//    fastparse.parse(input, statelessScript(_))
//  }
//}
