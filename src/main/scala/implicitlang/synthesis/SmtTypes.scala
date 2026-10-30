package implicitlang.synthesis

import implicitlang.core.Type
import implicitlang.smt.*
import implicitlang.smt.Smt.*

/** The query's type algebra, scoped substitution, and canonical unordered-record encoding. */
private[synthesis] final class SmtTypes(labels: Vector[String]) {
  sealed trait TypeValue
  sealed trait FieldsValue
  type TypeExpression = SmtExpr[TypeValue]

  val sort: SmtSort.Data[TypeValue] = SmtSort.Data("Type")
  private val fieldsSort: SmtSort.Data[FieldsValue] = SmtSort.Data("Fields")
  private val integer = SmtConstructor("Integer", sort, Vector.empty)
  private val string = SmtConstructor("String", sort, Vector.empty)
  private val boolean = SmtConstructor("Boolean", sort, Vector.empty)
  private val variable = SmtConstructor("Variable", sort, Vector("index" -> SmtSort.Int))
  private val arrow = SmtConstructor("Arrow", sort, Vector("parameter" -> sort, "result" -> sort))
  private val implicitArrow = SmtConstructor(
    "Implicit", sort, Vector("implicit_parameter" -> sort, "implicit_result" -> sort)
  )
  private val forall = SmtConstructor("Forall", sort, Vector("body" -> sort))
  private val record = SmtConstructor("Record", sort, Vector("fields" -> fieldsSort))
  private val empty = SmtConstructor("Empty", fieldsSort, Vector.empty)
  private val field = SmtConstructor(
    "Field", fieldsSort, Vector("label" -> SmtSort.Int, "value" -> sort, "tail" -> fieldsSort)
  )

  val datatypes: Vector[SmtDatatype[?]] = Vector(
    SmtDatatype(sort, Vector(integer, string, boolean, variable, arrow, implicitArrow, forall, record)),
    SmtDatatype(fieldsSort, Vector(empty, field))
  )

  private val wellFormed = SmtFunction("well_formed", Vector(sort, SmtSort.Int, SmtSort.Int), SmtSort.Bool)
  private val wellFormedFields = SmtFunction(
    "well_formed_fields", Vector(fieldsSort, SmtSort.Int, SmtSort.Int, SmtSort.Int), SmtSort.Bool
  )
  private val shift = SmtFunction("shift", Vector(sort, SmtSort.Int, SmtSort.Int), sort)
  private val shiftFields = SmtFunction("shift_fields", Vector(fieldsSort, SmtSort.Int, SmtSort.Int), fieldsSort)
  private val substitute = SmtFunction("instantiate", Vector(sort, sort, SmtSort.Int), sort)
  private val substituteFields = SmtFunction("instantiate_fields", Vector(fieldsSort, sort, SmtSort.Int), fieldsSort)

  val definitions: Vector[SmtDefinition[?]] = {
    val tpe = SmtConstant("t", sort)
    val fields = SmtConstant("fs", fieldsSort)
    val depth = SmtConstant("depth", SmtSort.Int)
    val height = SmtConstant("height", SmtSort.Int)
    val previous = SmtConstant("previous", SmtSort.Int)
    val argument = SmtConstant("argument", sort)
    val amount = SmtConstant("amount", SmtSort.Int)
    val nested = depth + int(1)
    val shorter = height - int(1)
    val index = variable.field(0, SmtSort.Int)(tpe)
    val label = field.field(0, SmtSort.Int)(fields)
    val fieldValue = field.field(1, sort)(fields)
    val tail = field.field(2, fieldsSort)(fields)

    def part(constructor: SmtConstructor[TypeValue], index: Int): TypeExpression = {
      constructor.field(index, sort)(tpe)
    }
    def valid(value: TypeExpression, scope: SmtExpr[BigInt] = depth): SmtExpr[Boolean] = {
      wellFormed(value, scope, shorter)
    }
    def cases[A](otherwise: SmtExpr[A], alternatives: (SmtConstructor[TypeValue], SmtExpr[A])*): SmtExpr[A] = {
      alternatives.foldRight(otherwise) { case ((constructor, result), otherwise) =>
        choose(constructor.is(tpe), result, otherwise)
      }
    }

    val validity = cases(
      bool(true),
      variable -> ((int(0) <= index) && (index < depth)),
      arrow -> (valid(part(arrow, 0)) && valid(part(arrow, 1))),
      implicitArrow -> (valid(part(implicitArrow, 0)) && valid(part(implicitArrow, 1))),
      forall -> valid(part(forall, 0), nested),
      record -> wellFormedFields(record.field(0, fieldsSort)(tpe), depth, shorter, int(-1))
    )
    val validFields = choose(empty.is(fields), bool(true), all(Vector(
      label > previous, label < int(labels.size), wellFormed(fieldValue, depth, height),
      wellFormedFields(tail, depth, height, label)
    )))

    def shifted(value: TypeExpression, cutoff: SmtExpr[BigInt] = depth): TypeExpression = {
      shift(value, cutoff, amount)
    }
    val shiftedType = cases(
      tpe,
      variable -> variable(choose(index >= depth, index + amount, index)),
      arrow -> arrow(shifted(part(arrow, 0)), shifted(part(arrow, 1))),
      implicitArrow -> implicitArrow(shifted(part(implicitArrow, 0)), shifted(part(implicitArrow, 1))),
      forall -> forall(shifted(part(forall, 0), nested)),
      record -> record(shiftFields(record.field(0, fieldsSort)(tpe), depth, amount))
    )
    val shiftedFields = choose(
      empty.is(fields), empty(), field(label, shifted(fieldValue), shiftFields(tail, depth, amount))
    )

    // [B/0] beneath k binders: k ↦ ↑ᵏB; i > k ↦ i−1; i < k remains bound locally.
    def substituted(value: TypeExpression, binders: SmtExpr[BigInt] = depth): TypeExpression = {
      substitute(value, argument, binders)
    }
    val substitutedType = cases(
      tpe,
      variable -> choose(
        index === depth, shift(argument, int(0), depth), variable(choose(index > depth, index - int(1), index))
      ),
      arrow -> arrow(substituted(part(arrow, 0)), substituted(part(arrow, 1))),
      implicitArrow -> implicitArrow(substituted(part(implicitArrow, 0)), substituted(part(implicitArrow, 1))),
      forall -> forall(substituted(part(forall, 0), nested)),
      record -> record(substituteFields(record.field(0, fieldsSort)(tpe), argument, depth))
    )
    val substitutedFields = choose(
      empty.is(fields), empty(), field(label, substituted(fieldValue), substituteFields(tail, argument, depth))
    )

    Vector(
      SmtDefinition(wellFormed, Vector(tpe, depth, height), choose(height > int(0), validity, bool(false))),
      SmtDefinition(wellFormedFields, Vector(fields, depth, height, previous), validFields),
      SmtDefinition(shift, Vector(tpe, depth, amount), shiftedType),
      SmtDefinition(shiftFields, Vector(fields, depth, amount), shiftedFields),
      SmtDefinition(substitute, Vector(tpe, argument, depth), substitutedType),
      SmtDefinition(substituteFields, Vector(fields, argument, depth), substitutedFields)
    )
  }

  def implicitFunction(parameter: TypeExpression, result: TypeExpression): TypeExpression = {
    implicitArrow(parameter, result)
  }
  def polymorphic(value: TypeExpression): SmtExpr[Boolean] = forall.is(value)
  def instantiate(value: TypeExpression, argument: TypeExpression): TypeExpression = {
    substitute(forall.field(0, sort)(value), argument, int(0))
  }
  def validArgument(value: TypeExpression, depth: Int, maximumHeight: Int): SmtExpr[Boolean] = {
    wellFormed(value, int(depth), int(maximumHeight))
  }

  def encode(tpe: Type): TypeExpression = encode(tpe, Vector.empty, 0)

  /** Interpret eliminated binders symbolically; retained ∀ binders still use de Bruijn indices. */
  def encode(tpe: Type, arguments: Vector[TypeExpression], ambientDepth: Int): TypeExpression = {
    def loop(tpe: Type, depth: Int): TypeExpression = tpe match {
      case Type.IntType => integer()
      case Type.StringType => string()
      case Type.BoolType => boolean()
      case Type.Variable(index) if index < depth => variable(int(index))
      case Type.Variable(index) if index - depth < arguments.size => {
        val argument = arguments(index - depth)
        if depth == 0 || ambientDepth == 0 then argument else shift(argument, int(0), int(depth))
      }
      case Type.Variable(index) => variable(int(index - arguments.size))
      case Type.Arrow(parameter, result) => arrow(loop(parameter, depth), loop(result, depth))
      case Type.Implicit(parameter, result) => implicitFunction(loop(parameter, depth), loop(result, depth))
      case Type.Forall(body) => forall(loop(body, depth + 1))
      case Type.Record(fields) => {
        val encoded = fields.toVector.sortBy(_._1).foldRight[SmtExpr[FieldsValue]](empty()) {
          case ((label, tpe), tail) => {
            val index = labels.indexOf(label)
            require(index >= 0, s"Record label outside this query's vocabulary: $label")
            field(int(index), loop(tpe, depth), tail)
          }
        }
        record(encoded)
      }
    }
    loop(tpe, 0)
  }

  /** Called after the type-argument validity obligations have been discharged. */
  def decode(value: SmtValue): Type = value match {
    case SmtValue.Constructor("Integer", Vector()) => Type.IntType
    case SmtValue.Constructor("String", Vector()) => Type.StringType
    case SmtValue.Constructor("Boolean", Vector()) => Type.BoolType
    case SmtValue.Constructor("Variable", Vector(SmtValue.Integer(index))) => Type.Variable(index.toInt)
    case SmtValue.Constructor("Arrow", Vector(parameter, result)) => Type.Arrow(decode(parameter), decode(result))
    case SmtValue.Constructor("Implicit", Vector(parameter, result)) => Type.Implicit(decode(parameter), decode(result))
    case SmtValue.Constructor("Forall", Vector(body)) => Type.Forall(decode(body))
    case SmtValue.Constructor("Record", Vector(fields)) => Type.Record(decodeFields(fields))
    case _ => throw new IllegalStateException("Solver returned a value outside the type datatype")
  }

  private def decodeFields(value: SmtValue): Map[String, Type] = value match {
    case SmtValue.Constructor("Empty", Vector()) => Map.empty
    case SmtValue.Constructor("Field", Vector(SmtValue.Integer(label), value, tail)) => {
      decodeFields(tail).updated(labels(label.toInt), decode(value))
    }
    case _ => throw new IllegalStateException("Solver returned a value outside the record-field datatype")
  }
}
