package implicitlang.synthesis

import implicitlang.core.Type

import com.microsoft.z3.{BoolExpr, BoolSort, Constructor, Context, DatatypeSort, Expr, IntSort, Sort}

/** Per-query encoding. No Z3 expressions or native resources escape the enclosing synthesis request. */
private[synthesis] final class SmtTypes(context: Context, labels: Vector[String]) {
  type TypeSort = DatatypeSort[Object]
  type TypeExpression = Expr[TypeSort]

  private def constructor(name: String, fields: (String, Sort, Int)*): Constructor[Object] = {
    context.mkConstructor[Object](
      name, s"is_$name", fields.map(_._1).toArray, fields.map(_._2).toArray, fields.map(_._3).toArray
    )
  }

  private val integer = constructor("Integer")
  private val string = constructor("String")
  private val boolean = constructor("Boolean")
  private val variable = constructor("Variable", ("index", context.getIntSort, 0))
  private val arrow = constructor("Arrow", ("parameter", null, 0), ("result", null, 0))
  private val implicitArrow = constructor("Implicit", ("implicit_parameter", null, 0), ("implicit_result", null, 0))
  private val forall = constructor("Forall", ("body", null, 0))
  private val record = constructor("Record", ("fields", null, 1))
  private val empty = constructor("Empty")
  private val field = constructor("Field", ("label", context.getIntSort, 0), ("value", null, 0), ("tail", null, 1))
  private val sorts = context.mkDatatypeSorts(
    Array("Type", "Fields"),
    Array(Array(integer, string, boolean, variable, arrow, implicitArrow, forall, record), Array(empty, field))
  )
  val sort: TypeSort = sorts(0)
  private val fieldsSort = sorts(1)

  private def build(constructor: Constructor[Object], arguments: Expr[?]*): TypeExpression = {
    constructor.ConstructorDecl().apply(arguments*)
  }

  private def is(constructor: Constructor[Object], value: TypeExpression): BoolExpr = {
    constructor.getTesterDecl.apply(value).asInstanceOf[BoolExpr]
  }

  // Accessor sorts are fixed by the datatype declarations above; casts stay inside this encoding boundary.
  private def part(constructor: Constructor[Object], value: TypeExpression, index: Int): TypeExpression = {
    constructor.getAccessorDecls()(index).apply(value).asInstanceOf[TypeExpression]
  }

  private def number(constructor: Constructor[Object], value: TypeExpression): Expr[IntSort] = {
    constructor.getAccessorDecls()(0).apply(value).asInstanceOf[Expr[IntSort]]
  }

  private def choose(
    value: TypeExpression, alternatives: Vector[(Constructor[Object], TypeExpression)]
  ): TypeExpression = {
    alternatives.foldRight(value) { case ((constructor, result), otherwise) =>
      context.mkITE(is(constructor, value), result, otherwise)
    }
  }

  private val wellFormed = context.mkRecFuncDecl(
    context.mkSymbol("well_formed"), Array(sort, context.getIntSort, context.getIntSort), context.getBoolSort
  )
  private val wellFormedFields = context.mkRecFuncDecl(
    context.mkSymbol("well_formed_fields"),
    Array(fieldsSort, context.getIntSort, context.getIntSort, context.getIntSort), context.getBoolSort
  )
  private val shift = context.mkRecFuncDecl(
    context.mkSymbol("shift"), Array(sort, context.getIntSort, context.getIntSort), sort
  )
  private val shiftFields = context.mkRecFuncDecl(
    context.mkSymbol("shift_fields"), Array(fieldsSort, context.getIntSort, context.getIntSort), fieldsSort
  )
  private val substitute = context.mkRecFuncDecl(
    context.mkSymbol("instantiate"), Array(sort, sort, context.getIntSort), sort
  )
  private val substituteFields = context.mkRecFuncDecl(
    context.mkSymbol("instantiate_fields"), Array(fieldsSort, sort, context.getIntSort), fieldsSort
  )

  private def defineOperations(): Unit = {
    val tpe = context.mkConst("t", sort)
    val fields = context.mkConst("fs", fieldsSort)
    val depth = context.mkIntConst("depth")
    val height = context.mkIntConst("height")
    val previous = context.mkIntConst("previous")
    val argument = context.mkConst("argument", sort)
    val amount = context.mkIntConst("amount")
    val one = context.mkInt(1)
    val zero = context.mkInt(0)
    val nested = context.mkAdd(depth, one)
    val shorter = context.mkSub(height, one)
    def valid(value: TypeExpression, scope: Expr[IntSort] = depth): Expr[BoolSort] = {
      wellFormed.apply(value, scope, shorter)
    }
    val cases: Vector[(Constructor[Object], Expr[BoolSort])] = Vector(
      variable -> context.mkAnd(context.mkLe(zero, number(variable, tpe)), context.mkLt(number(variable, tpe), depth)),
      arrow -> context.mkAnd(valid(part(arrow, tpe, 0)), valid(part(arrow, tpe, 1))),
      implicitArrow -> context.mkAnd(valid(part(implicitArrow, tpe, 0)), valid(part(implicitArrow, tpe, 1))),
      forall -> valid(part(forall, tpe, 0), nested),
      record -> wellFormedFields.apply(part(record, tpe, 0), depth, shorter, context.mkInt(-1))
    )
    val validity = cases.foldRight[Expr[BoolSort]](context.mkTrue()) { case ((constructor, result), otherwise) =>
      context.mkITE(is(constructor, tpe), result, otherwise)
    }
    context.AddRecDef(
      wellFormed, Array(tpe, depth, height),
      context.mkITE(context.mkGt(height, zero), validity, context.mkFalse())
    )
    context.AddRecDef(wellFormedFields, Array(fields, depth, height, previous), context.mkITE(
      is(empty, fields), context.mkTrue(),
      context.mkAnd(
        context.mkGt(number(field, fields), previous),
        context.mkLt(number(field, fields), context.mkInt(labels.size)),
        wellFormed.apply(part(field, fields, 1), depth, height),
        wellFormedFields.apply(part(field, fields, 2), depth, height, number(field, fields))
      )
    ))

    def shifted(value: TypeExpression, cutoff: Expr[IntSort] = depth): TypeExpression = {
      shift.apply(value, cutoff, amount)
    }
    context.AddRecDef(shift, Array(tpe, depth, amount), choose(tpe, Vector(
      variable -> build(variable, context.mkITE(
        context.mkGe(number(variable, tpe), depth),
        context.mkAdd(number(variable, tpe), amount), number(variable, tpe)
      )),
      arrow -> build(arrow, shifted(part(arrow, tpe, 0)), shifted(part(arrow, tpe, 1))),
      implicitArrow -> build(implicitArrow, shifted(part(implicitArrow, tpe, 0)), shifted(part(implicitArrow, tpe, 1))),
      forall -> build(forall, shifted(part(forall, tpe, 0), nested)),
      record -> build(record, shiftFields.apply(part(record, tpe, 0), depth, amount))
    )))
    context.AddRecDef(shiftFields, Array(fields, depth, amount), context.mkITE(
      is(empty, fields), build(empty),
      build(
        field, number(field, fields), shifted(part(field, fields, 1)),
        shiftFields.apply(part(field, fields, 2), depth, amount)
      )
    ))

    // [B/0] beneath k binders: k ↦ ↑ᵏB; i > k ↦ i−1; i < k remains bound locally.
    def substituted(value: TypeExpression, binders: Expr[IntSort] = depth): TypeExpression = {
      substitute.apply(value, argument, binders)
    }
    context.AddRecDef(substitute, Array(tpe, argument, depth), choose(tpe, Vector(
      variable -> context.mkITE(
        context.mkEq(number(variable, tpe), depth), shift.apply(argument, zero, depth),
        build(variable, context.mkITE(
          context.mkGt(number(variable, tpe), depth),
          context.mkSub(number(variable, tpe), one), number(variable, tpe)
        ))
      ),
      arrow -> build(arrow, substituted(part(arrow, tpe, 0)), substituted(part(arrow, tpe, 1))),
      implicitArrow -> build(
        implicitArrow, substituted(part(implicitArrow, tpe, 0)), substituted(part(implicitArrow, tpe, 1))
      ),
      forall -> build(forall, substituted(part(forall, tpe, 0), nested)),
      record -> build(record, substituteFields.apply(part(record, tpe, 0), argument, depth))
    )))
    context.AddRecDef(substituteFields, Array(fields, argument, depth), context.mkITE(
      is(empty, fields), build(empty),
      build(
        field, number(field, fields), substituted(part(field, fields, 1)),
        substituteFields.apply(part(field, fields, 2), argument, depth)
      )
    ))
  }
  defineOperations()

  def explicit(parameter: TypeExpression, result: TypeExpression): TypeExpression = build(arrow, parameter, result)
  def implicitFunction(parameter: TypeExpression, result: TypeExpression): TypeExpression = {
    build(implicitArrow, parameter, result)
  }
  def polymorphic(value: TypeExpression): BoolExpr = is(forall, value)
  def instantiate(value: TypeExpression, argument: TypeExpression): TypeExpression = {
    substitute.apply(part(forall, value, 0), argument, context.mkInt(0))
  }
  def validArgument(value: TypeExpression, depth: Int, maximumHeight: Int): Expr[BoolSort] = {
    wellFormed.apply(value, context.mkInt(depth), context.mkInt(maximumHeight))
  }

  def encode(tpe: Type): TypeExpression = encode(tpe, Vector.empty, 0)

  /** Interpret eliminated binders symbolically; retained ∀ binders still use de Bruijn indices. */
  def encode(tpe: Type, arguments: Vector[TypeExpression], ambientDepth: Int): TypeExpression = {
    def loop(tpe: Type, depth: Int): TypeExpression = tpe match {
      case Type.IntType => build(integer)
      case Type.StringType => build(string)
      case Type.BoolType => build(boolean)
      case Type.Variable(index) if index < depth => build(variable, context.mkInt(index))
      case Type.Variable(index) if index - depth < arguments.size => {
        val argument = arguments(index - depth)
        // Accepted arguments are well formed at ambientDepth. A closed argument has no free indices to shift.
        if depth == 0 || ambientDepth == 0 then argument
        else shift.apply(argument, context.mkInt(0), context.mkInt(depth))
      }
      case Type.Variable(index) => build(variable, context.mkInt(index - arguments.size))
      case Type.Arrow(parameter, result) => explicit(loop(parameter, depth), loop(result, depth))
      case Type.Implicit(parameter, result) => implicitFunction(loop(parameter, depth), loop(result, depth))
      case Type.Forall(body) => build(forall, loop(body, depth + 1))
      // Canonical serialization makes SMT datatype equality coincide with unordered map equality.
      case Type.Record(fields) => {
        val encoded = fields.toVector.sortBy(_._1).foldRight(build(empty)) { case ((label, tpe), tail) =>
          val index = labels.indexOf(label)
          require(index >= 0, s"Record label outside this query's vocabulary: $label")
          build(field, context.mkInt(index), loop(tpe, depth), tail)
        }
        build(record, encoded)
      }
    }
    loop(tpe, 0)
  }

  /** Decode a fully evaluated datatype value, never a display string. */
  def decode(value: Expr[?]): Type = {
    val declaration = value.getFuncDecl
    val children = value.getArgs
    if declaration == integer.ConstructorDecl() then Type.IntType
    else if declaration == string.ConstructorDecl() then Type.StringType
    else if declaration == boolean.ConstructorDecl() then Type.BoolType
    else if declaration == variable.ConstructorDecl() then Type.Variable(integerValue(children(0)))
    else if declaration == arrow.ConstructorDecl() then Type.Arrow(decode(children(0)), decode(children(1)))
    else if declaration == implicitArrow.ConstructorDecl() then Type.Implicit(decode(children(0)), decode(children(1)))
    else if declaration == forall.ConstructorDecl() then Type.Forall(decode(children(0)))
    else if declaration == record.ConstructorDecl() then Type.Record(decodeFields(children(0)))
    else throw new IllegalStateException("Z3 returned a value outside the type datatype")
  }

  private def decodeFields(value: Expr[?]): Map[String, Type] = {
    if value.getFuncDecl == empty.ConstructorDecl() then Map.empty
    else if value.getFuncDecl == field.ConstructorDecl() then {
      val arguments = value.getArgs
      val label = labels(integerValue(arguments(0)))
      decodeFields(arguments(2)).updated(label, decode(arguments(1)))
    } else throw new IllegalStateException("Z3 returned a value outside the record-field datatype")
  }

  private def integerValue(value: Expr[?]): Int = value match {
    case integer: com.microsoft.z3.IntNum => integer.getInt
    case _ => throw new IllegalStateException("Z3 returned a non-numeral datatype index")
  }
}
