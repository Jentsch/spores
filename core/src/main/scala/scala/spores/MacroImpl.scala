/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.spores

// TODO(jvican): Can we just use blackbox?
import scala.reflect.macros.whitebox

private[spores] class MacroImpl[C <: whitebox.Context with Singleton](val c: C) {
  import c.universe._

  /* Don't change this name since it's used
   * to check if a class is indeed a spore */
  val anonSporeName = TypeName("anonspore")

  def conforms(funTree: c.Tree): (List[Symbol], Type, Tree, List[Symbol]) = {
    val analysis = new SporeAnalysis[c.type](c)
    val (sporeEnv, sporeFunDef) = analysis.stripSporeStructure(funTree)
    sporeEnv foreach (sym => debug(s"Valid captured symbol: $sym"))

    val (funOpt, vparams, sporeBody) = analysis.readSporeFunDef(sporeFunDef)
    val s = funOpt.map(_.symbol)
    val captured = analysis.collectCaptured(sporeBody)
    val declared = analysis.collectDeclared(sporeBody)
    val checker = new SporeChecker[c.type](c)(sporeEnv, s, captured, declared)

    debug(s"Checking $sporeBody...")
    checker.checkReferencesInBody(sporeBody)
    (vparams.map(_.symbol), sporeBody.tpe, sporeBody, sporeEnv)
  }

  def check2(funTree: c.Tree, tpes: List[c.Type]): c.Tree = {
    debug(s"SPORES: enter check2")

    val (paramSyms, retTpe, funBody, validEnv) = conforms(funTree)

    val applyParamNames = for (i <- 0 until paramSyms.size)
      yield c.freshName(TermName("x" + i))
    val ids = for (name <- applyParamNames.toList) yield Ident(name)

    val applyParamValDefs =
      for ((applyParamName, paramSym) <- applyParamNames.zip(paramSyms))
        yield
          ValDef(Modifiers(Flag.PARAM),
                 applyParamName,
                 TypeTree(paramSym.typeSignature),
                 EmptyTree)
    val applyParamSymbols = for (applyParamValDef <- applyParamValDefs)
      yield applyParamValDef.symbol

    def mkApplyDefDef(body: Tree): DefDef = {
      val applyVParamss = List(applyParamValDefs.toList)
      DefDef(NoMods,
             TermName("apply"),
             Nil,
             applyVParamss,
             TypeTree(retTpe),
             body)
    }

    val symtable = c.universe.asInstanceOf[scala.reflect.internal.SymbolTable]

    def processFunctionBody(m: Map[c.Symbol, Tree], funBody: Tree): DefDef = {
      val newFunBody = transformTypes(m)(funBody)
      val nfBody = c.untypecheck(newFunBody.asInstanceOf[Tree])
      mkApplyDefDef(nfBody)
    }

    val sporeClassName = c.freshName(anonSporeName)

    if (validEnv.isEmpty) {
      // replace references to paramSyms with references to applyParamSymbols
      val m = paramSyms.zip(ids).toMap
      val applyDefDef = processFunctionBody(m, funBody)

      if (paramSyms.size == 2) {
        q"""
          class $sporeClassName extends scala.spores.Spore2[${tpes(1)}, ${tpes(
          2)}, ${tpes(0)}] {
            self =>
            type Captured = scala.Nothing
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName
        """
      } else if (paramSyms.size == 3) {
        q"""
          class $sporeClassName extends scala.spores.Spore3[${tpes(1)}, ${tpes(
          2)}, ${tpes(3)}, ${tpes(0)}] {
            self =>
            type Captured = scala.Nothing
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName
        """
      } else ???
    } else { // validEnv.size > 1 (TODO: size == 1)
      // replace references to paramSyms with references to applyParamSymbols
      // and references to captured variables to new fields
      val capturedTypes = validEnv.map(_.typeSignature)
      debug(s"capturedTypes: ${capturedTypes.mkString(",")}")

      val symsToReplace = paramSyms ::: validEnv
      val newTrees =
        if (validEnv.size == 1)
          List(Select(Ident(TermName("self")), TermName("captured")))
        else
          (1 to validEnv.size)
            .map(
              i =>
                Select(Select(Ident(TermName("self")), TermName("captured")),
                       TermName(s"_$i")))
            .toList

      val treesToSubstitute = ids ::: newTrees
      val m = symsToReplace.zip(treesToSubstitute).toMap
      val applyDefDef = processFunctionBody(m, funBody)

      val rhss = funTree match {
        case Block(stmts, expr) =>
          stmts.toList flatMap {
            case ValDef(_, _, _, rhs) => List(rhs)
            case stmt =>
              c.error(stmt.pos, "Only val defs allowed at this position")
              List()
          }
      }

      val constructorParams = List(List(toTuple(rhss)))

      val captureTypeTreeDefinition =
        (if (capturedTypes.size == 1) q"type Captured = ${capturedTypes(0)}"
         else if (capturedTypes.size == 2)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)})"
         else if (capturedTypes.size == 3)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)})"
         else if (capturedTypes.size == 4) q"type Captured = (${capturedTypes(
           0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)})"
         else if (capturedTypes.size == 5)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
             2)}, ${capturedTypes(3)}, ${capturedTypes(4)})"
         else if (capturedTypes.size == 6)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
             2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)})"
         else if (capturedTypes.size == 7)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(
             3)}, ${capturedTypes(4)}, ${capturedTypes(5)}, ${capturedTypes(6)})"
         else if (capturedTypes.size == 8) q"type Captured = (${capturedTypes(
           0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(
           4)}, ${capturedTypes(5)}, ${capturedTypes(6)}, ${capturedTypes(7)})")
          .asInstanceOf[c.Tree]

      val q"type $_ = $captureTypeTree" = captureTypeTreeDefinition

      if (paramSyms.size == 2) {
        q"""
            class $sporeClassName(val captured: $captureTypeTree) extends scala.spores.Spore2WithEnv[${tpes(
          1)}, ${tpes(2)}, ${tpes(0)}] {
              self =>
              $captureTypeTreeDefinition
              this._className = this.getClass.getName
              $applyDefDef
            }
            new $sporeClassName(...$constructorParams)
          """
      } else if (paramSyms.size == 3) {
        q"""
            class $sporeClassName(val captured: $captureTypeTree) extends scala.spores.Spore3WithEnv[${tpes(
          1)}, ${tpes(2)}, ${tpes(3)}, ${tpes(0)}] {
              self =>
              $captureTypeTreeDefinition
              this._className = this.getClass.getName
              $applyDefDef
            }
            new $sporeClassName(...$constructorParams)
          """
      } else ???
    }
  }

  /**
     spore {
       val x = outer
       delayed { ... }
     }
    */
  def checkNullary(funTree: c.Tree, rtpe: c.Type): c.Tree = {
    debug(s"SPORES: enter checkNullary")

    val (paramSyms, retTpe, funBody, validEnv) = conforms(funTree)

    val applyName = TermName("apply")
    val symtable = c.universe.asInstanceOf[scala.reflect.internal.SymbolTable]
    val sporeClassName = c.freshName(anonSporeName)

    if (validEnv.isEmpty) {
      val newFunBody = c.untypecheck(funBody)
      val applyDefDef = DefDef(NoMods,
                               applyName,
                               Nil,
                               List(List()),
                               TypeTree(retTpe),
                               newFunBody)

      q"""
        class $sporeClassName extends scala.spores.NullarySpore[$rtpe] {
          type Captured = Nothing
          this._className = this.getClass.getName
          $applyDefDef
        }
        new $sporeClassName
      """
    } else {
      val capturedTypes = validEnv.map(_.typeSignature)
      debug(s"capturedTypes: ${capturedTypes.mkString(",")}")

      // replace references to captured variables with references to new fields
      val symsToReplace = validEnv
      val newTrees =
        if (validEnv.size == 1)
          List(Select(Ident(TermName("self")), TermName("captured")))
        else
          (1 to validEnv.size)
            .map(
              i =>
                Select(Select(Ident(TermName("self")), TermName("captured")),
                       TermName(s"_$i")))
            .toList
      val treesToSubstitute = newTrees
      val symsToTrees = symsToReplace.zip(treesToSubstitute).toMap
      val newFunBody = transformTypes(symsToTrees)(funBody)

      val nfBody = c.untypecheck(newFunBody.asInstanceOf[c.universe.Tree])
      val applyDefDef =
        DefDef(NoMods, applyName, Nil, List(List()), TypeTree(retTpe), nfBody)

      val rhss = funTree match {
        case Block(stmts, expr) =>
          stmts.toList flatMap { stmt =>
            stmt match {
              case vd @ ValDef(mods, name, tpt, rhs) => List(rhs)
              case _ =>
                c.error(stmt.pos, "Only val defs allowed at this position")
                List()
            }
          }
      }
      assert(rhss.size == validEnv.size)

      val constructorParams = List(List(toTuple(rhss)))

      val superclassName = TypeName("NullarySporeWithEnv")

      val captureTypeTreeDefinition =
        (if (capturedTypes.size == 1) q"type Captured = ${capturedTypes(0)}"
         else if (capturedTypes.size == 2)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)})"
         else if (capturedTypes.size == 3)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)})"
         else if (capturedTypes.size == 4) q"type Captured = (${capturedTypes(
           0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)})"
         else if (capturedTypes.size == 5)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
             2)}, ${capturedTypes(3)}, ${capturedTypes(4)})"
         else if (capturedTypes.size == 6)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
             2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)})"
         else if (capturedTypes.size == 7)
           q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(
             3)}, ${capturedTypes(4)}, ${capturedTypes(5)}, ${capturedTypes(6)})"
         else if (capturedTypes.size == 8) q"type Captured = (${capturedTypes(
           0)}, ${capturedTypes(1)}, ${capturedTypes(2)}, ${capturedTypes(3)}, ${capturedTypes(
           4)}, ${capturedTypes(5)}, ${capturedTypes(6)}, ${capturedTypes(7)})")
          .asInstanceOf[c.Tree]

      val q"type $_ = $captureTypeTree" = captureTypeTreeDefinition

      q"""
        class $sporeClassName(val captured: $captureTypeTree) extends $superclassName[$rtpe] {
          self =>
          $captureTypeTreeDefinition
          this._className = this.getClass.getName
          $applyDefDef
        }
        new $sporeClassName(...$constructorParams)
      """
    }
  }

  /**  Constructs a function that replaces all occurrences of symbols in m with trees in m and that changes the 'origin'
    *  field to fix path-dependent types.
    *  In some cases, PTT:s that start with captured variables or the spore parameters are not traversed fully.
    *  The syntax tree part of these PTTs shows as "TypeTree()", but the ().tpe part has additional structure.
    *  The 'TypeTree' case transforms these types by adding an "().original" field, that is a syntax tree.
    *  The syntax tree is constructed by replacing all TypeName(s) occurances where s is the name of a captured
    *  variable or a parameter into nameMap(s). E.g.
    *  TypeRef(SingleType(SingleType(NoPrefix, TermName("param")), TypeName("R") --> Select(nameMap("param"), TypeName("R"))
    */
  def transformTypes(m: Map[c.universe.Symbol, Tree]): Tree => Tree = {
    class TypeTransformer(
        val m: Map[c.universe.Symbol, Tree] //,
        //val nameMap: Map[c.universe.Symbol, Tree]
    ) extends Transformer {
      override def transform(tree: Tree): Tree = {

        tree match {
          case Ident(_) => m.getOrElse(tree.symbol, tree)
          case tt: TypeTree if tt.original != null =>
            super.transform(
              c.universe.internal
                .setOriginal(TypeTree(), super.transform(tt.original)))
          case tt: TypeTree if tt.original == null =>
            if (tt.children.isEmpty &&
                m.keys.exists(key => tt.tpe.contains(key))) {
              debug(s"${showRaw(tree)}")
              debug(s"${showRaw(tree.tpe)}")
              debug(s"${tree.tpe}")

              /**
                * Recursively construct a Tree from a Type
                * @param tp
                *           Any type, typically looks like this:
                *           TypeRef(
                *              SingleType(
                *                 SingleType(NoPrefix, TermName("lit5_ui")),
                *                 TermName("uref")),
                *              TypeName("R"),
                *              List())
                * @return
                *         A syntax tree constructed from the type. The example would return
                *         Select(
                *            Select(
                *               Ident(TermName("lit5_ui")),
                *               TermName("uref")),
                *            TermName("R"))
                *         Returns null if the param is not a path-dependent type
                */
              def constructOriginal(tp: c.Type): c.Tree = {
                def matchTypeName(tn: c.Symbol): c.TypeName = {
                  tn match {
                    case TypeSymbolTag(ts) => {
                      ts.name.toTypeName
                    }
                    case _ => null
                  }
                }
                def matchTermNameNoPrefixCase(tn: c.Symbol): Tree =
                  m.getOrElse(tn, null)

                def matchTermName(tn: c.Symbol): TermName = {
                  tn match {
                    case TermSymbolTag(ts) => ts.name.toTermName
                    case _ => null
                  }
                }
                tp match {
                  case TypeRef(tr, tns, List()) =>
                    debug(s"tns = $tns,\nshowRaw(tns) = ${showRaw(tns)}")
                    val tnsTypeName = matchTypeName(tns)
                    if (tnsTypeName != null) {
                      val tr_rec = constructOriginal(tr)
                      if (tr_rec != null) Select(tr_rec, tnsTypeName)
                      else null
                    } else null
                  case SingleType(NoPrefix, tns) =>
                    matchTermNameNoPrefixCase(tns)
                  case SingleType(pre, tns) =>
                    val tnsTypeName = matchTermName(tns)
                    val pre_rec = constructOriginal(pre)
                    if (pre_rec != null) Select(pre_rec, tnsTypeName)
                    else null
                  case _ => null
                }
              }

              val new_orig = constructOriginal(tree.tpe)
              val res =
                if (new_orig != null)
                  c.universe.internal.setOriginal(TypeTree(), new_orig)
                else
                  tree
              res
            } else tree
          case _ => super.transform(tree)
        }
      }
    }
    new TypeTransformer(m).transform(_: Tree)
  }

  def toTuple(lst: List[c.Tree]): c.Tree = {
    if (lst.size == 1) lst(0)
    else if (lst.size == 2) q"(${lst(0)}, ${lst(1)})"
    else if (lst.size == 3) q"(${lst(0)}, ${lst(1)}, ${lst(2)})"
    else if (lst.size == 4) q"(${lst(0)}, ${lst(1)}, ${lst(2)}, ${lst(3)})"
    else if (lst.size == 5)
      q"(${lst(0)}, ${lst(1)}, ${lst(2)}, ${lst(3)}, ${lst(4)})"
    else if (lst.size == 6)
      q"(${lst(0)}, ${lst(1)}, ${lst(2)}, ${lst(3)}, ${lst(4)}, ${lst(5)})"
    else if (lst.size == 7)
      q"(${lst(0)}, ${lst(1)}, ${lst(2)}, ${lst(3)}, ${lst(4)}, ${lst(5)}, ${lst(6)})"
    else if (lst.size == 8) q"(${lst(0)}, ${lst(1)}, ${lst(2)}, ${lst(3)}, ${lst(
      4)}, ${lst(5)}, ${lst(6)}, ${lst(7)})"
    else ???
  }

  /**
     spore {
       val x = outer
       (y: T) => { ... }
     }
    */
  def check(funTree: c.Tree, ttpe: c.Type, rtpe: c.Type): c.Tree = {
    debug(s"SPORES: enter check, tree:\n$funTree")

    val (paramSyms, retTpe, funBody, validEnv) = conforms(funTree)
    val paramSym = paramSyms.head
    val oldName = paramSym.asInstanceOf[c.universe.TermSymbol].name

    if (paramSym != null) {
      val applyParamName = c.freshName(TermName("x"))
      val id = Ident(applyParamName)
      val applyName = TermName("apply")

      val applyParamValDef = ValDef(Modifiers(Flag.PARAM),
                                    applyParamName,
                                    TypeTree(paramSym.typeSignature),
                                    EmptyTree)
      val sporeClassName = c.freshName(anonSporeName)

      if (validEnv.isEmpty) {
        val newFunBody = transformTypes(Map(paramSym -> id))(funBody)
        val nfBody = c.untypecheck(newFunBody.asInstanceOf[c.universe.Tree])

        val applyDefDef: DefDef = {
          val applyVParamss = List(List(applyParamValDef))
          DefDef(NoMods,
                 applyName,
                 Nil,
                 applyVParamss,
                 TypeTree(retTpe),
                 nfBody)
        }

        q"""
          class $sporeClassName extends scala.spores.Spore[$ttpe, $rtpe] {
            self =>
            type Captured = scala.Nothing
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName
        """
      } else {
        // replace reference to paramSym with reference to applyParamSymbol
        // and references to captured variables with references to new fields
        val capturedTypes = validEnv.map(_.typeSignature)
        debug(s"capturedTypes: ${capturedTypes.mkString(",")}")

        val symsToReplace = paramSym :: validEnv
        val newTrees =
          if (validEnv.size == 1)
            List(Select(Ident(TermName("self")), TermName("captured")))
          else
            (1 to validEnv.size)
              .map(
                i =>
                  Select(Select(Ident(TermName("self")), TermName("captured")),
                         TermName(s"_$i")))
              .toList

        val treesToSubstitute = id :: newTrees
        val symsToTrees = symsToReplace.zip(treesToSubstitute).toMap
        val namesToTrees =
          symsToReplace.map(_.name.toString).zip(treesToSubstitute).toMap
        val newFunBody = transformTypes(symsToTrees)(funBody)

        val nfBody = c.untypecheck(newFunBody.asInstanceOf[c.universe.Tree])
        val applyDefDef: DefDef = {
          val applyVParamss = List(List(applyParamValDef))
          DefDef(NoMods,
                 applyName,
                 Nil,
                 applyVParamss,
                 TypeTree(retTpe),
                 nfBody)
        }

        val rhss = funTree match {
          case Block(stmts, expr) =>
            stmts.toList flatMap { stmt =>
              stmt match {
                case vd @ ValDef(mods, name, tpt, rhs) => List(rhs)
                case _ =>
                  c.error(stmt.pos, "Only val defs allowed at this position")
                  List()
              }
            }
        }
        assert(rhss.size == validEnv.size)

        val initializerName = c.freshName(TermName("initialize"))
        val constructorParams = List(List(toTuple(rhss)))
        val superclassName = TypeName("SporeWithEnv")

        val capturedTypeDefinition =
          (if (capturedTypes.size == 1) q"type Captured = ${capturedTypes(0)}"
           else if (capturedTypes.size == 2)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)})"
           else if (capturedTypes.size == 3)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(2)})"
           else if (capturedTypes.size == 4)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
               2)}, ${capturedTypes(3)})"
           else if (capturedTypes.size == 5)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
               2)}, ${capturedTypes(3)}, ${capturedTypes(4)})"
           else if (capturedTypes.size == 6)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
               2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(5)})"
           else if (capturedTypes.size == 7)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
               2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(
               5)}, ${capturedTypes(6)})"
           else if (capturedTypes.size == 8)
             q"type Captured = (${capturedTypes(0)}, ${capturedTypes(1)}, ${capturedTypes(
               2)}, ${capturedTypes(3)}, ${capturedTypes(4)}, ${capturedTypes(
               5)}, ${capturedTypes(6)}, ${capturedTypes(7)})")
            .asInstanceOf[c.Tree]

        val q"type $_ = $capturedTypeTree" = capturedTypeDefinition

        q"""
          class $sporeClassName(val captured : $capturedTypeTree) extends $superclassName[$ttpe, $rtpe] {
            self =>
            $capturedTypeDefinition
            this._className = this.getClass.getName
            $applyDefDef
          }
          new $sporeClassName(...$constructorParams)
        """
      }
    } else {
      ???
    }
  }

}
