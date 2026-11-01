package implicitlang.smt

import implicitlang.util.Result

sealed trait SmtSort[A] {
  def name: String
}

object SmtSort {
  case object Bool extends SmtSort[Boolean] { val name = "Bool" }
  case object Int extends SmtSort[BigInt] { val name = "Int" }
  final case class Data[A](name: String) extends SmtSort[A] {
    require(Smt.validName(name), s"Invalid SMT sort name: $name")
  }
}

/** Immutable, sorted formulas with structural inspection and SMT-LIB transport. */
sealed trait SmtExpr[A] {
  def sort: SmtSort[A]
  def smtlib: String

  final def ===(other: SmtExpr[A]): SmtExpr[Boolean] = {
    require(sort == other.sort, "Equality operands must have the same SMT sort")
    Smt.application("=", SmtSort.Bool, Vector(this, other))
  }
}

final case class SmtConstant[A](name: String, sort: SmtSort[A]) extends SmtExpr[A] {
  require(Smt.validName(name), s"Invalid SMT constant name: $name")
  def smtlib: String = name
  def declaration: String = s"(declare-const $name ${sort.name})"
}

final case class SmtFunction[A](name: String, parameters: Vector[SmtSort[?]], result: SmtSort[A]) {
  require(Smt.validName(name), s"Invalid SMT function name: $name")

  def apply(arguments: SmtExpr[?]*): SmtExpr[A] = {
    require(arguments.map(_.sort).toVector == parameters, s"Incorrect argument sorts for $name")
    Smt.application(name, result, arguments.toVector)
  }
}

final case class SmtConstructor[A](name: String, sort: SmtSort.Data[A], fields: Vector[(String, SmtSort[?])]) {
  val function: SmtFunction[A] = SmtFunction(name, fields.map(_._2), sort)

  def apply(arguments: SmtExpr[?]*): SmtExpr[A] = function(arguments*)
  def is(value: SmtExpr[A]): SmtExpr[Boolean] = {
    require(value.sort == sort, "A constructor test must use its datatype's sort")
    Smt.constructorTest(this, value)
  }

  def field[B](index: Int, expected: SmtSort[B]): SmtFunction[B] = {
    require(fields(index)._2 == expected, s"Incorrect selector sort for $name")
    SmtFunction(fields(index)._1, Vector(sort), expected)
  }

  def declaration: String = (name +: fields.map((name, sort) => s"($name ${sort.name})")).mkString("(", " ", ")")
}

final case class SmtDatatype[A](sort: SmtSort.Data[A], constructors: Vector[SmtConstructor[A]]) {
  require(constructors.nonEmpty && constructors.forall(_.sort == sort))
}

final case class SmtDefinition[A](function: SmtFunction[A], parameters: Vector[SmtConstant[?]], body: SmtExpr[A]) {
  require(function.parameters == parameters.map(_.sort) && function.result == body.sort)

  def signature: String = {
    val arguments = parameters.map(value => s"(${value.name} ${value.sort.name})").mkString(" ")
    s"(${function.name} ($arguments) ${function.result.name})"
  }
}

object Smt {
  private[smt] def validName(value: String): Boolean = value.matches("[A-Za-z_][A-Za-z0-9_]*")

  final case class Application[A] private[smt] (
    operator: String, sort: SmtSort[A], arguments: Vector[SmtExpr[?]]
  ) extends SmtExpr[A] {
    def smtlib: String = {
      if arguments.isEmpty then operator else (operator +: arguments.map(_.smtlib)).mkString("(", " ", ")")
    }
  }

  private[smt] def application[A](operator: String, sort: SmtSort[A], arguments: Vector[SmtExpr[?]]): SmtExpr[A] = {
    Application(operator, sort, arguments)
  }

  final case class ConstructorTest[A] private[smt] (constructor: SmtConstructor[A], value: SmtExpr[A])
      extends SmtExpr[Boolean] {
    val sort: SmtSort[Boolean] = SmtSort.Bool
    def smtlib: String = s"((_ is ${constructor.name}) ${value.smtlib})"
  }

  final case class BooleanLiteral(value: Boolean) extends SmtExpr[Boolean] {
    val sort: SmtSort[Boolean] = SmtSort.Bool
    def smtlib: String = value.toString
  }

  final case class IntegerLiteral(value: BigInt) extends SmtExpr[BigInt] {
    val sort: SmtSort[BigInt] = SmtSort.Int
    def smtlib: String = if value < 0 then s"(- ${-value})" else value.toString
  }

  private[smt] def constructorTest[A](constructor: SmtConstructor[A], value: SmtExpr[A]): SmtExpr[Boolean] = {
    ConstructorTest(constructor, value)
  }

  def bool(value: Boolean): SmtExpr[Boolean] = BooleanLiteral(value)
  def int(value: BigInt): SmtExpr[BigInt] = IntegerLiteral(value)

  def all(values: Iterable[SmtExpr[Boolean]]): SmtExpr[Boolean] = {
    if values.isEmpty then bool(true) else Application("and", SmtSort.Bool, values.toVector)
  }

  def any(values: Iterable[SmtExpr[Boolean]]): SmtExpr[Boolean] = {
    if values.isEmpty then bool(false) else Application("or", SmtSort.Bool, values.toVector)
  }

  def sum(values: Iterable[SmtExpr[BigInt]]): SmtExpr[BigInt] = {
    if values.isEmpty then int(0) else Application("+", SmtSort.Int, values.toVector)
  }

  def choose[A](condition: SmtExpr[Boolean], yes: SmtExpr[A], no: SmtExpr[A]): SmtExpr[A] = {
    require(yes.sort == no.sort, "Conditional branches must have the same SMT sort")
    Application("ite", yes.sort, Vector(condition, yes, no))
  }

  extension (left: SmtExpr[Boolean]) {
    def &&(right: SmtExpr[Boolean]): SmtExpr[Boolean] = all(Vector(left, right))
    def ||(right: SmtExpr[Boolean]): SmtExpr[Boolean] = any(Vector(left, right))
    def unary_! : SmtExpr[Boolean] = Application("not", SmtSort.Bool, Vector(left))
    def implies(right: SmtExpr[Boolean]): SmtExpr[Boolean] = Application("=>", SmtSort.Bool, Vector(left, right))
  }

  extension (left: SmtExpr[BigInt]) {
    def +(right: SmtExpr[BigInt]): SmtExpr[BigInt] = Application("+", SmtSort.Int, Vector(left, right))
    def -(right: SmtExpr[BigInt]): SmtExpr[BigInt] = Application("-", SmtSort.Int, Vector(left, right))
    def <(right: SmtExpr[BigInt]): SmtExpr[Boolean] = Application("<", SmtSort.Bool, Vector(left, right))
    def <=(right: SmtExpr[BigInt]): SmtExpr[Boolean] = Application("<=", SmtSort.Bool, Vector(left, right))
    def >(right: SmtExpr[BigInt]): SmtExpr[Boolean] = Application(">", SmtSort.Bool, Vector(left, right))
    def >=(right: SmtExpr[BigInt]): SmtExpr[Boolean] = Application(">=", SmtSort.Bool, Vector(left, right))
  }
}

/** Caller-owned provenance accompanies the formula without affecting its meaning. */
final case class SmtConstraint[+Origin](origin: Origin, formula: SmtExpr[Boolean])
final case class SmtObservation(name: String, expression: SmtExpr[?])

final case class SmtProblem[Origin](
  datatypes: Vector[SmtDatatype[?]],
  definitions: Vector[SmtDefinition[?]],
  constants: Vector[SmtConstant[?]],
  constraints: Vector[SmtConstraint[Origin]],
  observations: Vector[SmtObservation]
) {
  require(constants.map(_.name).distinct.size == constants.size, "SMT constant names must be unique")
  require(observations.map(_.name).distinct.size == observations.size, "SMT observation names must be unique")

  private def prelude: String = {
    val sorts = datatypes.map(value => s"(${value.sort.name} 0)").mkString(" ")
    val constructors = datatypes.map(_.constructors.map(_.declaration).mkString("(", " ", ")")).mkString(" ")
    val declarations = if datatypes.isEmpty then {
      Vector.empty
    } else {
      Vector(s"(declare-datatypes ($sorts) ($constructors))")
    }
    val recursive = if definitions.isEmpty then {
      Vector.empty
    } else {
      Vector(
        s"(define-funs-rec (${definitions.map(_.signature).mkString(" ")}) " +
        s"(${definitions.map(_.body.smtlib).mkString(" ")}))"
      )
    }
    (declarations ++ recursive ++ constants.map(_.declaration)).mkString("\n")
  }

  def assertions: String = constraints.map(value => s"(assert ${value.formula.smtlib})").mkString("\n")

  /** Complete replayable query, including recursive definitions and model observations. */
  def smtlib(timeoutMillis: Int, includeModel: Boolean = true): String = {
    val observe = if observations.isEmpty then {
      ""
    } else {
      s"\n(get-value (${observations.map(_.expression.smtlib).mkString(" ")}))"
    }
    val model = if includeModel then s"\n(get-model)$observe" else ""
    s"(set-option :timeout $timeoutMillis)\n$prelude\n$assertions\n(check-sat)$model\n"
  }

  /**
   * Parse observation expressions together with their declarations. Adapters assert only the first
   * constraints.size formulas; the trailing equations are handles for model evaluation, not constraints.
   */
  def parsingScript: String = {
    val handles = observations.zipWithIndex.map { case (observation, index) =>
      val name = s"|@observation:$index|"
      s"(declare-const $name ${observation.expression.sort.name})\n(assert (= $name ${observation.expression.smtlib}))"
    }
    (Vector(prelude, assertions) ++ handles).mkString("\n")
  }
}

enum SmtValue {
  case Integer(value: BigInt)
  case Boolean(value: scala.Boolean)
  case Constructor(name: String, arguments: Vector[SmtValue])
}

final case class SmtModel(values: Map[String, SmtValue], text: String) {
  def apply(observation: String): SmtValue = values.getOrElse(
    observation, throw new IllegalStateException(s"Solver omitted model observation $observation")
  )
}

enum SmtAnswer {
  case Sat(model: SmtModel)
  case Unsat
  case Unknown(reason: String)
}

final case class SmtFailure(cause: Throwable)
final case class SmtResponse(answer: SmtAnswer, elapsedMillis: Double, statistics: Map[String, String])

/**
 * Each check owns its solver resources. Returned models contain immutable values only. F describes
 * the execution effect: identity on the JVM, an asynchronous effect in a browser. Backend failures
 * remain distinct from unsatisfiability and from a solver's unknown answer.
 */
trait SmtSolver[F[_]] {
  def check[Origin](problem: SmtProblem[Origin], timeoutMillis: Int): F[Result[SmtResponse, SmtFailure]]
}
