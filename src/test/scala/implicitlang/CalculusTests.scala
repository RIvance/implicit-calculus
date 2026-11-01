package implicitlang

import implicitlang.`implicit`.{ImplicitTerm, Elaborated}
import implicitlang.cli.Pipeline
import implicitlang.core.{Type, Term, OpKind, Value, Evaluator, TypeChecker}
import implicitlang.diagnostic.Error
import implicitlang.surface.{SurfaceExpr, SurfaceType, Program, Declaration, Parser, Elaborator}
import implicitlang.synthesis.{Synthesizer, SynthesisContext, NaiveSynthesizer, SmtSynthesizer, SynthesisLimits}
import implicitlang.util.Result

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Dependency-free executable tests, also wired to `sbt test`. */
object CalculusTests {
  private var passed = 0
  private var failed = 0

  private def test(name: String)(body: => Unit): Unit = {
    try {
      body
      passed += 1
      println(s"PASS $name")
    } catch {
      case NonFatal(error) => {
        failed += 1
        System.err.println(s"FAIL $name: ${error.getMessage}")
        error.printStackTrace()
      }
    }
  }

  private def success[A](result: Result[A, Error]): A = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => throw new AssertionError(s"Unexpected error: $error")
  }

  private def expectFailure[A](result: Result[A, Error])(predicate: Error => Boolean): Unit = result match {
    case Result.Err(error) => assert(predicate(error), s"Unexpected error: $error")
    case Result.Ok(value) => throw new AssertionError(s"Expected failure, received: $value")
  }

  private def run(source: String): Value = success(Pipeline.run(source))

  private def parse(source: String): Program = success(new Parser().parse(source))

  private def expression(source: String): SurfaceExpr = parse(source) match {
    case Program(Vector(), body) => body
    case other => throw new AssertionError(s"Expected an expression without top-level declarations: $other")
  }

  private def testExamples(directory: Path): Unit = {
    val paths = Using.resource(Files.walk(directory)) { stream =>
      stream.iterator().asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".imp"))
        .toVector.sortBy(_.toString)
    }
    assert(paths.nonEmpty, s"No .imp examples found in $directory")
    paths.foreach { path =>
      test("example " + directory.relativize(path)) {
        val source = Files.readString(path)
        val expected: Result[String, String] = source.linesIterator.nextOption() match {
          case Some(line) if line.startsWith("// result:") => {
            Result.Ok(line.stripPrefix("// result:").stripPrefix(" "))
          }
          case Some(line) if line.startsWith("// error:") => {
            Result.Err(line.stripPrefix("// error:").stripPrefix(" "))
          }
          case _ => throw new AssertionError("First line must be // result: value or // error: message")
        }
        val actual = Pipeline.run(source).map(_.show).mapError(_.toString)
        assert(actual == expected, s"Expected $expected, received $actual")
      }
    }
  }

  def main(arguments: Array[String]): Unit = {
    test("comma-separated and repeated definition parameters produce the same curried expression") {
      val compact = "def choose[T](a: T, b: T): T = a in choose[Int](21, 99)"
      val curried = "def choose [T] (a: T) (b: T) : T = a in choose [Int] 21 99"
      assert(parse(compact) == parse(curried))
      assert(run(curried) == Value.Integer(21))
      assert(run("def sum(a: Int, b: Int)(c: Int): Int = a + b + c in sum(1, 2)(3)") == Value.Integer(6))
    }

    test("lambda parameter groups are equivalent to nested unary lambdas") {
      val expected = parse("(a: Int) => (b: Int) => a + b")
      Vector("(a: Int, b: Int) => a + b", "(a: Int)(b: Int) => a + b")
        .foreach(source => assert(parse(source) == expected))
      assert(run("((a: Int, b: Int) => a + b) 20 1") == Value.Integer(21))
    }

    test("ordinary lambda annotations are optional in bare grouped and mixed parameter syntax") {
      val identity = SurfaceExpr.Lam("x", None, SurfaceExpr.Var("x"))
      Vector("x => x", "(x) => x", "{ x => x }").foreach(source => assert(expression(source) == identity))
      assert(parse("(x, y) => x") == parse("x => y => x"))
      assert(parse("(x: Int, y) => y") == parse("(x: Int) => y => y"))
      assert(parse("(x)(implicit y: Int, z) => z") == parse("x => (implicit y: Int) => z => z"))
      Vector("(implicit x) => x", "def f(x): Int = x in 0", "instance f(x): Int = x in 0").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("ascriptions and evidence annotations provide expected lambda types") {
      Vector(
        "let add: Int -> Int -> Int = (x, y) => x + y in add(20, 1)",
        "let add: Int -> Int -> Int = (x: Int, y) => x + y in add(20, 1)",
        "let add = ((x, y) => x + y): Int -> Int -> Int in add(20, 1)",
        "given add: Int -> Int -> Int = (x, y) => x + y in add(20, 1)",
        "given id: forall A. A -> A = [A] => x => x in id[Int](21)",
        "instance id[A]: A -> A = x => x in id[Int](21)",
        "def id: Int -> Int = x => x in id(21)"
      ).foreach(source => assert(run(source) == Value.Integer(21)))
    }

    test("ordinary and implicit applications check lambda arguments against parameter types") {
      Vector(
        "def apply(f: Int -> Int): Int = f(20) in apply(x => x + 1)",
        "def apply(implicit f: Int -> Int): Int = f(20) in apply(using x => x + 1)",
        "def apply(f: forall A. A -> A): Int = f[Int](21) in apply([A] => x => x)",
        "def apply(f: Int -> Int -> Int): Int = f(20, 1) in apply((x: Int, y) => x + y)"
      ).foreach(source => assert(run(source) == Value.Integer(21)))
    }

    test("expected types flow through bindings type abstractions and record fields") {
      val source = """
        |type Functions[A] = { identity: A -> A, keep: A -> (Int ~> A) }
        |let functions: forall A. Functions[A] = [A] => {
        |  let seed = 0
        |  given offset: Int = seed
        |  { identity = x => x, keep = x => (implicit ignored: Int) => x }
        |}
        |functions[Int].identity(functions[Int].keep(21)(using 0))
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
      val implementation = "trait Step[A] = { step: A -> A } in " +
        "implement[A] Step[A] = { step = x => x } in Step[Int]::step(21)"
      assert(run(implementation) == Value.Integer(21))
      assert(run("let f: Int ~> Int -> Int = (implicit x: Int) => y => x + y in f(using 20)(1)") ==
        Value.Integer(21))
      Vector(
        "let r: { run: Int -> Int, flag: Bool } = { run = x => x } in r",
        "let r: { run: Int -> Int } = { run = x => x, extra = 0 } in r"
      ).foreach { source =>
        expectFailure(Pipeline.compile(source)) {
          case Error.ExpectedType(Type.Record(expected), Type.Record(actual)) => expected.keySet != actual.keySet
          case _ => false
        }
      }
    }

    test("recursive definitions check unannotated lambdas against the declared signature") {
      val source = "def run: ((Int -> Int) -> Int) -> Int = " +
        "step => step(value => run(ignored => value)) in run(again => again(21))"
      assert(run(source) == Value.Integer(21))
    }

    test("unannotated lambdas require an expected function type rather than inference from use") {
      Vector("x => x", "x => x + 1", "let id = x => x in id(21)").foreach { source =>
        expectFailure(Pipeline.compile(source)) { case Error.MissingParameterType("x") => true; case _ => false }
      }
      expectFailure(Pipeline.compile("let f: Int -> Int = (x: String) => 21 in f")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
      expectFailure(Pipeline.compile("let f: Int -> Int = x => true in f")) {
        case Error.ExpectedType(Type.IntType, Type.BoolType) => true
        case _ => false
      }
      expectFailure(Pipeline.compile("let f: Int ~> Int = x => x in f")) {
        case Error.ExpectedFunction(Type.Implicit(Type.IntType, Type.IntType)) => true
        case _ => false
      }
    }

    test("program elaboration checks externally supplied expected types and validates their scope") {
      val program = parse("type Number = Int in x => x")
      val expected = Type.Arrow(Type.IntType, Type.IntType)
      val checked = success(new Elaborator().elaborate(program, Some(expected)))
      assert(checked == Elaborated(ImplicitTerm.Lam(Type.IntType, ImplicitTerm.Var(0), Some("x")), expected))
      expectFailure(new Elaborator().elaborate(program, Some(Type.Arrow(Type.Variable(0), Type.Variable(0))))) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
    }

    test("mixed lambda parameters preserve each binder kind and their source order") {
      val expected = SurfaceExpr.Lam(
        "a",
        Some(SurfaceType.IntType),
        SurfaceExpr.ImplicitLam(
          "b", SurfaceType.StringType, SurfaceExpr.Lam("c", Some(SurfaceType.BoolType), SurfaceExpr.Var("b"))
        )
      )
      Vector(
        "(a: Int, implicit b: String, c: Bool) => b",
        "(a: Int)(implicit b: String)(c: Bool) => b",
        "(a: Int) => (implicit b: String) => (c: Bool) => b"
      ).foreach(source => assert(expression(source) == expected))
      val compilation = success(Pipeline.compile("(a: Int, implicit b: String, c: Bool) => b"))
      assert(compilation.elaborated.tpe == Type.Arrow(
        Type.IntType,
        Type.Implicit(Type.StringType, Type.Arrow(Type.BoolType, Type.StringType))
      ))
    }

    test("mixed definition parameters support implicit search and explicit overrides") {
      val definition = "def choose[T](a: T, implicit b: T, c: T): T = b"
      val curried = "def choose [T] (a: T) (implicit b: T) (c: T): T = b"
      assert(parse(s"$definition in choose") == parse(s"$curried in choose"))
      val prefix = s"$definition\ninstance selected: Int = 21\n"
      assert(run(prefix + "choose[Int](1, 99)") == Value.Integer(21))
      assert(run(prefix + "choose[Int] 1 99") == Value.Integer(21))
      assert(run(prefix + "choose[Int](1)(using 42)(99)") == Value.Integer(42))
    }

    test("only individually marked parameters enter implicit scope") {
      val source = """
        |let f = (a: Int, implicit b: Int, c: Int) => a + summon[Int] + c
        |f(1)(using 20)(100)
        |""".stripMargin
      assert(run(source) == Value.Integer(121))
      expectFailure(Pipeline.compile("(implicit b: Int, c: String) => summon[String]")) {
        case Error.MissingInstance(Type.StringType) => true
        case _ => false
      }
    }

    test("where constraints and mixed parameter groups compose in declaration order") {
      val source = """
        |trait Default[T] = { value: T }
        |def choose[T where Default[T]](ignored: T, implicit selected: T)(last: T): T = summon[T]
        |implement Default[Int] = { value = 21 }
        |choose[Int](0)(using 42)(1)
        |""".stripMargin
      assert(run(source) == Value.Integer(42))
    }

    test("a trailing implicit parameter remains a first-class implicit function") {
      val source = "let f = (a: Int, implicit b: Int) => a + b in f(1)"
      assert(success(Pipeline.compile(source)).elaborated.tpe == Type.Implicit(Type.IntType, Type.IntType))
      assert(run(s"($source)(using 20)") == Value.Integer(21))
      assert(run("def identity(implicit x: Int): Int = x in identity(using 21)") == Value.Integer(21))
    }

    test("implicit type arrows use tilde while lambda bodies retain equals arrows") {
      val source = "let identity: Int ~> Int = (implicit x: Int) => x in identity(using 21)"
      assert(run(source) == Value.Integer(21))
      val expected = SurfaceType.Arrow(
        SurfaceType.IntType,
        SurfaceType.Implicit(SurfaceType.StringType, SurfaceType.Arrow(SurfaceType.BoolType, SurfaceType.IntType))
      )
      assert(expression("f: Int -> String ~> Bool -> Int") == SurfaceExpr.Ascribe(SurfaceExpr.Var("f"), expected))
      assert(
        Type.Implicit(Type.IntType, Type.Arrow(Type.StringType, Type.BoolType)).show == "(Int ~> (String -> Bool))"
      )
      Vector("summon[Int => Int]", "(f: Int => Int) => f").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("lambda parameters and record literals remain unambiguous") {
      assert(run("([A, B] => (a: A, implicit b: B) => a)[Int, String](21)(using \"evidence\")") == Value.Integer(21))
      assert(run("let fun = 21 in fun") == Value.Integer(21))
      assert(run("((implicitValue: Int) => implicitValue) 21") == Value.Integer(21))
      assert(run("{ value = 21 }.value") == Value.Integer(21))
      Vector(
        "fun {d: Int} => d",
        "{d: Int} => d",
        "fun (x: Int) => x",
        "fun [A] => (x: A) => x",
        "(implicit: Int) => implicit"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("ordinary argument lists and whitespace applications associate to the left") {
      val expected = SurfaceExpr.App(SurfaceExpr.App(SurfaceExpr.Var("f"), SurfaceExpr.Var("a")), SurfaceExpr.Var("b"))
      Vector("f(a, b)", "f(a)(b)", "f a b", "f (a, b)", "f(a) b", "f a (b)", "f (a) (b)").foreach { source =>
        assert(expression(source) == expected)
      }
      assert(expression("f [Int] a b") == expression("f[Int](a, b)"))
      assert(expression("f(using d) a b") == expression("f(using d)(a, b)"))
    }

    test("brace blocks and parentheses produce the same expression AST") {
      Vector("21", "1 + 2 * 3", "let x = 20 in x + 1", "let x = 20\nx + 1", "(x: Int) => x", "{ value = 21 }")
        .foreach { body =>
          assert(expression(s"{ $body }") == expression(s"($body)"))
        }
      assert(expression("{ { { 21 } } }") == SurfaceExpr.IntLit(21))
      assert(run("{ 1 + 2 } * 3") == Value.Integer(9))
    }

    test("brace arguments are ordinary applications including when adjacent to the function") {
      val expected = SurfaceExpr.App(SurfaceExpr.Var("f"), SurfaceExpr.Var("value"))
      Vector("f(value)", "f { value }", "f{value}", "f({ value })").foreach { source =>
        assert(expression(source) == expected)
      }
      assert(expression("f a { b }") == expression("f a (b)"))
      assert(expression("f a{b}") == expression("f a(b)"))
      assert(run("def f(x: Int): Int = x + 1 in f { let value = 20\nvalue }") == Value.Integer(21))
      assert(run("def f(x: Int): Int = x + 1 in f{20}") == Value.Integer(21))
    }

    test("records and brace blocks share ordinary argument parsing") {
      val expected = parse("f({ value = 21 })")
      Vector("f { value = 21 }", "f{value = 21}", "f { { value = 21 } }").foreach { source =>
        assert(parse(source) == expected)
      }
      assert(run("def get(r: { value: Int }): Int = r.value in get{value = 21}") == Value.Integer(21))
      assert(run("{ let r = { value = 21 }\nr }.value") == Value.Integer(21))
      assert(run("{}") == Value.Record(Map.empty))
    }

    test("brace blocks preserve expression scope without introducing a statement language") {
      val source = """
        |let x = 1
        |let y = {
        |  let x = 20
        |  given chosen: Int = x + 1
        |  summon[Int]
        |}
        |x + y
        |""".stripMargin
      assert(run(source) == Value.Integer(22))
      expectFailure(Pipeline.compile("let result = { given chosen: Int = 21\nsummon[Int] }\nsummon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
      Vector("{ let x = 21 }", "{ 21; 22 }").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("using marks each explicit implicit argument independently") {
      val expected = SurfaceExpr.App(
        SurfaceExpr.ImplicitApp(SurfaceExpr.App(SurfaceExpr.Var("f"), SurfaceExpr.Var("a")), SurfaceExpr.Var("b")),
        SurfaceExpr.Var("c")
      )
      assert(expression("f(a, using b, c)") == expected)
      assert(expression("f(a)(using b)(c)") == expected)
      assert(expression("f(using a, b)") == SurfaceExpr.App(
        SurfaceExpr.ImplicitApp(SurfaceExpr.Var("f"), SurfaceExpr.Var("a")), SurfaceExpr.Var("b")
      ))
      assert(expression("f(using a, using b)") == expression("f(using a)(using b)"))
      assert(run("def f(a: Int, implicit b: Int, c: Int): Int = a + b + c in f(1, using 20, 100)") ==
        Value.Integer(121))
      assert(run("def f(implicit a: Int, b: Int, implicit c: Int): Int = a + b + c in f(using 1, 2, using 3)") ==
        Value.Integer(6))
    }

    test("using arguments can contain brace blocks and explicitly override available evidence") {
      val source = """
        |instance fallback: Int = 99
        |let identity = (implicit x: Int) => x
        |identity(
        |  using {
        |    let answer = 20
        |    answer + 1
        |  }
        |)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
      expectFailure(Pipeline.compile("((implicit x: Int) => x)(using \"wrong\")")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("using is reserved only as a complete keyword and must precede an argument") {
      assert(run("let usingValue = 21 in ((x: Int) => x)(usingValue)") == Value.Integer(21))
      assert(parse("f(using /* explicit */ value)") == parse("f(using value)"))
      Vector("let using = 21 in using", "(using: Int) => using", "f(using)", "f using value", "f { using value }")
        .foreach { source =>
          expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
        }
    }

    test("postfix operations bind inside whitespace arguments and application precedes arithmetic") {
      assert(expression("f a.field") == SurfaceExpr.App(
        SurfaceExpr.Var("f"), SurfaceExpr.Select(SurfaceExpr.Var("a"), "field")
      ))
      assert(expression("f g(x)") == SurfaceExpr.App(
        SurfaceExpr.Var("f"), SurfaceExpr.App(SurfaceExpr.Var("g"), SurfaceExpr.Var("x"))
      ))
      assert(expression("f g (x)") == SurfaceExpr.App(
        SurfaceExpr.App(SurfaceExpr.Var("f"), SurfaceExpr.Var("g")), SurfaceExpr.Var("x")
      ))
      assert(expression("f g[Int]") == SurfaceExpr.App(
        SurfaceExpr.Var("f"), SurfaceExpr.TypeApp(SurfaceExpr.Var("g"), SurfaceType.IntType)
      ))
      assert(run("def twice(x: Int): Int = x * 2 in twice 3 + twice 4 * 5") == Value.Integer(46))
    }

    test("subtraction remains unambiguous beside application and negative literals") {
      Vector("10-2", "10 -2", "10 - 2").foreach(source => assert(run(source) == Value.Integer(8)))
      Vector("f(-1)", "f (-1)").foreach { call =>
        assert(run(s"def f(x: Int): Int = x in $call") == Value.Integer(-1))
      }
      assert(run("def f(x: Int): Int = x in f 10 -2") == Value.Integer(8))
    }

    test("typed lets parse as ascribed initializers and elaborate identically") {
      val ast = expression("let a = (21: Int) in a")
      val expected = success(Pipeline.compile("let a: Int = 21 in a")).elaborated
      Vector("let a: Int = 21 in a", "let a = (21: Int) in a", "let a: Int = 21\na").foreach { source =>
        assert(expression(source) == ast)
        assert(success(Pipeline.compile(source)).elaborated == expected)
        assert(run(source) == Value.Integer(21))
      }
      expectFailure(Pipeline.compile("let a: String = 21\na")) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("declarations and binding expressions share continuation separators") {
      val declarations = Vector(
        "let a = 21" -> "a",
        "given a: Int = 21" -> "summon[Int]",
        "instance a: Int = 21" -> "summon[Int]",
        "def f(x: Int): Int = x" -> "f 21",
        "type N = Int" -> "21: N",
        "trait C = { value: Int }" -> "implement C = { value = 21 } in C::value",
        "trait C = { value: Int } in implement C = { value = 21 }" -> "C::value",
        "instance selected: { value: Int } = { value = 21 }" -> "summon[{ value: Int }].value",
        "instance identity[A]: A -> A = (x: A) => x" -> "identity[Int](21)",
        "instance id[A]: A -> A = (x: A) => x" -> "summon[forall A. A -> A][Int](21)"
      )
      val separators = Vector(" in ", "\n", "\r\n", "\r", "; ", " // comment\n", " /* comment\n */ ")
      declarations.foreach { case (declaration, body) =>
        val expected = parse(s"$declaration in $body")
        separators.foreach { separator =>
          assert(parse(declaration + separator + body) == expected)
        }
      }
    }

    test("same-line declarations and bindings require a continuation separator") {
      Vector(
        "let a = 21 a",
        "instance a: Int = 21 summon[Int]",
        "def f(x: Int): Int = x f 21",
        "type N = Int 21",
        "trait C = { value: Int } 21",
        "let a = 21 /* no newline */ a",
        "let a = 21\n",
        "instance a: Int = 21\n"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("val is no longer a binding keyword") {
      expectFailure(new Parser().parse("val a = 21\na")) { case Error.Parse(_, _, _) => true; case _ => false }
      assert(run("let val = 21 in val") == Value.Integer(21))
    }

    test("newlines protect a binding body from being consumed as a postfix or whitespace argument") {
      assert(run("let ignored = 99\n((x: Int) => x) 21") == Value.Integer(21))
      assert(run("let ignored = 99\n{ value = 21 }.value") == Value.Integer(21))
      assert(run("let ignored = 99\n-21") == Value.Integer(-21))
      assert(run("let identity = (x: Int) => x\nidentity 21") == Value.Integer(21))
      assert(run("let a = let b = 20\nb + 1\na") == Value.Integer(21))
      assert(run("let a = (let b = 20\nb + 1)\na") == Value.Integer(21))
      assert(run("let a = 21\n\n// body\nin a") == Value.Integer(21))
    }

    test("line breaks are accepted after tokens that require an operand or body") {
      val source = """
        |def sum [T] (a: Int)
        |  (b: Int): Int =
        |  a +
        |  b
        |let value =
        |  sum[Int](
        |    20,
        |    1
        |  )
        |let identity = (x: Int) =>
        |  x
        |identity value
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("complete expressions do not continue application or operators across a newline") {
      Vector("f\nx", "f\n(x)", "f\n[Int]", "f\n{d}", "f\n.field", "1\n+ 2").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
      assert(parse("f /* argument */ x") == parse("f x"))
      assert(run("let a = 21 /* boundary\n */ a") == Value.Integer(21))
    }

    test("record types and values require comma semicolon or newline field separators") {
      Vector(", ", "; ", "\n", "\r\n", " // next field\n", " /* next\nfield */ ").foreach { separator =>
        val annotation = "{ a: Int" + separator + "b: Int }"
        val record = "{ a = f 20" + separator + "b = f 0 }"
        val source = s"def f(x: Int): Int = x + 1 in let r: $annotation = $record in r.a + r.b"
        assert(run(source) == Value.Integer(22))
      }
      Vector("{ a = 1 b = 2 }", "summon[{ a: Int b: Int }]").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
      Vector("{}", "{\n}").foreach(source => assert(run(source) == Value.Record(Map.empty)))
      Vector(",", ";", "\n").foreach { separator =>
        assert(run(s"({ a = 21$separator }: { a: Int$separator }).a") == Value.Integer(21))
      }
    }

    test("parser represents given as an expression with a body") {
      expression("given answer: Int = 21 in answer") match {
        case SurfaceExpr.Given("answer", SurfaceType.IntType, SurfaceExpr.IntLit(value), SurfaceExpr.Var("answer")) => {
          assert(value == 21)
        }
        case other => throw new AssertionError(s"Unexpected AST: $other")
      }
    }

    test("instance parameter lists expand to function annotations and lambda initializers") {
      val prefix = "type Counted[A] = { value: A, count: Int } in "
      val value = "{ value = value, count = count }"
      val compact = s"instance counted[A](value: A, implicit count: Int): Counted[A] = $value"
      val repeated = s"instance counted[A](value: A)(implicit count: Int): Counted[A] = $value"
      val expanded = s"instance counted[A]: A -> (Int ~> Counted[A]) = (value: A, implicit count: Int) => $value"
      val body = "counted[String](\"Ada\", using 21)"
      Vector(compact, repeated, expanded).foreach { binding =>
        assert(parse(s"$prefix$binding in $body") == parse(s"$prefix$expanded in $body"))
        assert(run(s"$prefix$binding in $body") ==
          Value.Record(Map("value" -> Value.Text("Ada"), "count" -> Value.Integer(21))))
      }
    }

    test("instance parameters compose with where constraints and mixed application order") {
      val source = """
        |trait Offset[A] = { offset: A }
        |implement Offset[Int] = { offset = 20 }
        |instance combine[A where Offset[A]](value: A, implicit choose: A -> A -> A)(last: A): A =
        |  choose(Offset[A]::offset)(choose(value)(last))
        |def add(left: Int, right: Int): Int = left + right
        |combine[Int](0, using add, 1)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
      expectFailure(Pipeline.compile("instance bad(value: Int): String = value in 0")) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("instances and given accept arbitrary types and expression initializers") {
      val prefix = "def identity(x: Int): Int = x in def existing: Int = 21 in "
      val cases = Vector(
        ("Int", "21", "summon[Int]"),
        ("Int", "existing", "summon[Int]"),
        ("Int", "identity(21)", "summon[Int]"),
        ("Int", "{ let x = 20\nx + 1 }", "summon[Int]"),
        ("Int -> Int", "(x: Int) => x + 1", "summon[Int -> Int](20)"),
        ("Int ~> Int", "(implicit x: Int) => x + 1", "summon[Int ~> Int](using 20)"),
        ("{ value: Int }", "{ value = 21 }", "summon[{ value: Int }].value")
      )
      cases.foreach { case (annotation, initializer, body) =>
        assert(run(s"${prefix}instance selected: $annotation = $initializer in $body") == Value.Integer(21))
        assert(run(s"${prefix}given selected: $annotation = $initializer in $body") == Value.Integer(21))
      }
    }

    test("given requires a name annotation initializer and body") {
      Vector(
        "given : Int = 21 in 0",
        "given n = 21 in n",
        "given n: Int in n",
        "given n: Int = 21",
        "given n[A]: A -> A = (x: A) => x in 0",
        "given n(x: Int): Int = x in 0"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
      val source = "given id: forall A. A -> A = [A] => (x: A) => x in id[Int](21)"
      assert(run(source) == Value.Integer(21))
      expectFailure(Pipeline.compile("given n: String = 21 in n")) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("instance names and type parameters are distinct from applied ordinary types") {
      val prefix = "type Box[A] = { value: A } in "
      assert(run(prefix + "instance box: Box[Int] = { value = 21 } in Box[Int]::value") == Value.Integer(21))
      assert(run(prefix + "instance box: Box[Box[Int]] = { value = { value = 21 } } in Box[Box[Int]]::value.value") ==
        Value.Integer(21))
      assert(run(prefix + "instance Box[A]: A -> A = (x: A) => x in Box[Int](21)") == Value.Integer(21))
      assert(run("type N = Int in instance n: N = 21 in summon[N]") == Value.Integer(21))
      assert(run("instance id[A]: A -> A = (x: A) => x in summon[forall A. A -> A][Int](21)") == Value.Integer(21))
    }

    test("type parameter clauses share where lowering and constraint order") {
      val annotation = "forall A, B. { value: A } ~> { value: B } ~> { first: A, second: B }"
      val initializer = "{ first = Default[A]::value, second = Default[B]::value }"
      val body = s"summon[$annotation][Int, String](using { value = 21 }, using { value = \"Ada\" })"
      val prefix = "trait Default[A] = { value: A } in trait Pair[A, B] = { first: A, second: B } in "
      val explicit = s"""instance pair: $annotation = [A, B] =>
        |(implicit a: { value: A }, implicit b: { value: B }) => { first = a.value, second = b.value } in $body
        |""".stripMargin
      val expected = success(Pipeline.compile(explicit)).target
      Vector(
        "instance pair[A, B where Default[A] and Default[B]]: { first: A, second: B }",
        "implement[A, B where Default[A] and Default[B]] Pair[A, B]"
      ).foreach { declaration =>
        val source = s"$prefix$declaration = $initializer in $body"
        assert(success(Pipeline.compile(source)).target == expected)
        assert(run(source) == Value.Record(Map("first" -> Value.Integer(21), "second" -> Value.Text("Ada"))))
      }
    }

    test("implementation clauses can introduce constraints without type parameters") {
      Vector("instance selected[where C]: { value: Int }", "implement[where C] C").foreach { declaration =>
        val source = s"trait C = { value: Int } in $declaration = { value = C::value } in " +
          "summon[{ value: Int } ~> { value: Int }](using { value = 21 }).value"
        assert(run(source) == Value.Integer(21))
      }
    }

    test("instances require names annotations and initializers") {
      Vector(
        "instance named: { value: Int } { value = 21 } in 0",
        "instance { value: Int } { value = 21 } in 0",
        "instance named[A]: { value: A -> A } { value = (x: A) => x } in 0",
        "instance [A]: { value: A -> A } { value = (x: A) => x } in 0",
        "instance [A] A -> A = (x: A) => x in 0",
        "instance named[A] A -> A = (x: A) => x in 0",
        "instance named: = 21 in 0",
        "instance : Int = 21 in 0",
        "instance Int = 21",
        "instance [A]: A -> A = (x: A) => x"
      ).foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("ordinary and implementation binders have separate indices") {
      val parsed = success(new Parser().parse("instance d: Int = 21 in (x: String) => d"))
      val actual = success(new Elaborator().elaborate(parsed)).expression
      assert(actual == ImplicitTerm.InstanceLet(
        Type.IntType,
        ImplicitTerm.IntLit(21),
        ImplicitTerm.Lam(Type.StringType, ImplicitTerm.Instance(0), Some("x")),
        Some("d")
      ))
    }

    test("name elaboration inserts a hole without attempting synthesis") {
      val parsed = success(new Parser().parse("summon[Int]"))
      assert(success(new Elaborator().elaborate(parsed)) == Elaborated(
        ImplicitTerm.Need(Type.IntType), Type.IntType
      ))
      expectFailure(Pipeline.compile("summon[Int]")) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("ordinary application checks its argument") {
      expectFailure(Pipeline.compile("((x: Int) => x)(\"bad\")")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("ordinary values are not implicitly registered") {
      expectFailure(Pipeline.compile("let ordinary = 21 in summon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("nearest matching implementation takes precedence") {
      assert(run("instance first: Int = 1 in instance second: Int = 2 in summon[Int]") == Value.Integer(2))
    }

    test("instances can be referred to and passed as ordinary values") {
      assert(run("instance value: Int = 21 in ((x: Int) => x)(value)") == Value.Integer(21))
    }

    test("an ordinary parameter can be installed as a local implementation") {
      assert(run("((x: Int) => given chosen: Int = x in summon[Int])(21)") == Value.Integer(21))
    }

    test("instance bindings are nonrecursive") {
      expectFailure(Pipeline.compile("instance self: Int = summon[Int] in summon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
      assert(run("instance outer: Int = 21 in instance self: Int = summon[Int] in summon[Int]") == Value.Integer(21))
      expectFailure(Pipeline.compile("instance self(n: Int): Int = self(n) in 0")) {
        case Error.UnknownTerm("self") => true
        case _ => false
      }
      expectFailure(Pipeline.compile("given self: Int = summon[Int] in self")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("implementation annotations are checked") {
      expectFailure(Pipeline.compile("instance wrong: Int = \"no\" in summon[Int]")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
      expectFailure(Pipeline.compile(
        "trait C[A] = { value: A } in instance wrong[A where C[A]]: Int = C[A]::value in 0"
      )) {
        case Error.ExpectedType(Type.IntType, Type.Variable(0)) => true
        case _ => false
      }
    }

    test("instances preserve lexical scope and nearest matching selection") {
      val source = """
        |instance first: Int = 11
        |let captured = (ignored: Int) => summon[Int]
        |given text: String = "Ada"
        |given second: Int = 21
        |{ before = captured(0), current = summon[Int], text = summon[String] }
        |""".stripMargin
      assert(run(source) == Value.Record(Map(
        "before" -> Value.Integer(11), "current" -> Value.Integer(21), "text" -> Value.Text("Ada")
      )))
      expectFailure(Pipeline.compile("let result = { given local: Int = 21\nsummon[Int] }\nsummon[Int]")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("ordinary binders weaken references to the implementation environment") {
      val source = "instance d: Int = 11 in let ignored = \"x\" in ((x: String) => summon[Int])(\"y\")"
      assert(run(source) == Value.Integer(11))
    }

    test("implementation binders weaken references to the ordinary environment") {
      val source = "let x = 11 in given d: String = \"a\" in ((implicit q: String) => x)(using \"b\")"
      assert(run(source) == Value.Integer(11))
    }

    test("source name shadowing does not remove an older implementation from search") {
      val source = "instance x: Int = 11 in let x = 100 in summon[Int] + x"
      assert(run(source) == Value.Integer(111))
    }

    test("implicit lambdas make their parameters available to synthesis") {
      assert(run("((implicit d: Int) => summon[Int])(using 21)") == Value.Integer(21))
    }

    test("ordinary function application inserts all leading implicit arguments") {
      val source = "instance d: Int = 21 in ((implicit a: Int) => (implicit b: Int) => (x: Int) => b + x)(1)"
      assert(run(source) == Value.Integer(22))
    }

    test("implicit application is not ordinary application") {
      expectFailure(Pipeline.compile("((x: Int) => x)(using 21)")) {
        case Error.ExpectedImplicitFunction(_) => true
        case _ => false
      }
    }

    test("implicit functions remain first-class when passed through an ordinary arrow") {
      val source = "((f: Int ~> Int) => f(using 21))((implicit x: Int) => x)"
      assert(run(source) == Value.Integer(21))
    }

    test("lookup distinguishes implicit and explicit arrows before erasure") {
      val source = "instance f: Int -> Int = (x: Int) => x in summon[Int ~> Int]"
      expectFailure(Pipeline.compile(source)) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("a bare implicit value is not forced merely by variable lookup") {
      val compilation = success(Pipeline.compile("(implicit x: Int) => x"))
      assert(compilation.elaborated.tpe == Type.Implicit(Type.IntType, Type.IntType))
      assert(success(TypeChecker.infer(compilation.target)) == Type.Arrow(Type.IntType, Type.IntType))
    }

    test("polymorphic identity checks and evaluates") {
      assert(run("([A] => (x: A) => x)[Int](21)") == Value.Integer(21))
    }

    test("type abstraction weakens types in both environments") {
      val source = "([A] => (implicit d: A) => (x: A) => [B] => summon[A])[Int](using 21)(99)[String]"
      assert(run(source) == Value.Integer(21))
    }

    test("polymorphic type substitution avoids capturing a nested type variable") {
      val body = Type.Forall(Type.Arrow(Type.Variable(1), Type.Variable(0)))
      assert(body.instantiate(Type.StringType) == Type.Forall(Type.Arrow(Type.StringType, Type.Variable(0))))
    }

    test("aliases capture their declaration environment rather than later type names") {
      val source = "type A = Int in type Saved = A in type A = String in let x: Saved = 21 in x"
      assert(run(source) == Value.Integer(21))
    }

    test("parameterized aliases instantiate beneath outer type variables") {
      val source = """
        |type Pair[A, B] = { first: A, second: B }
        |([A] => [C] => (p: Pair[A, C]) => p.first)[Int][String]({ first = 21, second = "Ada" })
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("record equality does not depend on field order") {
      val source = "((r: { a: Int, b: String }) => r.a)({ b = \"Ada\", a = 21 })"
      assert(run(source) == Value.Integer(21))
    }

    test("duplicate record labels are rejected rather than silently overwritten") {
      expectFailure(Pipeline.compile("{ x = 1, x = 2 }")) { case Error.Parse(_, _, _) => true; case _ => false }
    }

    test("alias arity is checked") {
      expectFailure(Pipeline.compile("type Box[A] = { value: A } in summon[Box]")) {
        case Error.TypeArity("Box", 1, 0) => true
        case _ => false
      }
    }

    test("keyword prefixes are not accepted as keyword tokens") {
      expectFailure(new Parser().parse("let x = 1 inner")) { case Error.Parse(_, _, _) => true; case _ => false }
      assert(run("let inner = 21 in inner") == Value.Integer(21))
    }

    test("string escapes and arbitrary precision integers are preserved") {
      assert(run("\"a\\n\\u0042\"") == Value.Text("a\nB"))
      assert(run("123456789012345678901234567890") == Value.Integer(BigInt("123456789012345678901234567890")))
    }

    test("parser constructs a shared binary operator AST directly") {
      val parsed = expression("1 + 2 * 3")
      assert(parsed == SurfaceExpr.BinOp(
        OpKind.Add,
        SurfaceExpr.IntLit(1),
        SurfaceExpr.BinOp(OpKind.Mul, SurfaceExpr.IntLit(2), SurfaceExpr.IntLit(3))
      ))
      assert(expression("\"a\" ++ \"b\"") == SurfaceExpr.BinOp(
        OpKind.Concat,
        SurfaceExpr.StringLit("a"),
        SurfaceExpr.StringLit("b")
      ))
    }

    test("arithmetic has conventional precedence and left associativity") {
      assert(run("1 + 2 * 3") == Value.Integer(7))
      assert(run("10 - 2 - 3") == Value.Integer(5))
      assert(run("(1 + 2) * 3") == Value.Integer(9))
    }

    test("comparison operators return Bool") {
      assert(run("1 + 2 * 3 == 7") == Value.Boolean(true))
      assert(run("2 < 1") == Value.Boolean(false))
      assert(run("3 > 1") == Value.Boolean(true))
      assert(run("true: Bool") == Value.Boolean(true))
    }

    test("string concatenation is ++, not numeric addition") {
      assert(run("\"a\" ++ \"b\" ++ \"c\"") == Value.Text("abc"))
      expectFailure(Pipeline.compile("\"a\" + \"b\"")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
      expectFailure(Pipeline.compile("\"a\" ++ 1")) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("operators are checked independently in the final calculus") {
      assert(success(TypeChecker.infer(Term.BinOp(OpKind.Lt, Term.IntLit(1), Term.IntLit(2)))) == Type.BoolType)
      expectFailure(TypeChecker.infer(Term.BinOp(OpKind.Add, Term.StringLit("bad"), Term.IntLit(1)))) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("integer-to-string conversion is not a primitive") {
      expectFailure(Pipeline.compile("showInt(21)")) {
        case Error.UnknownTerm("showInt") => true
        case _ => false
      }
    }

    testExamples(Path.of("examples"))

    test("SMT synthesis chooses the nearest exact implementation") {
      val context = SynthesisContext(List(Type.IntType, Type.IntType), typeDepth = 0)
      assert(success(new SmtSynthesizer().synthesize(Type.IntType, context)) == ImplicitTerm.Instance(0))
    }

    test("SMT synthesis composes implicit producers") {
      val source = """
        |instance text: String = "Ada"
        |instance toNumber: String ~> Int = (implicit text: String) => 21
        |instance toBool: Int ~> Bool = (implicit number: Int) => number > 0
        |summon[Bool]
        |""".stripMargin
      assert(run(source) == Value.Boolean(true))
    }

    test("SMT synthesis instantiates a producer with a function type") {
      val source = """
        |instance identity[A]: A -> A = (a: A) => a
        |let twice = summon[(Int -> Int) -> (Int -> Int)]
        |twice((n: Int) => n + 1)(20)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("SMT synthesis preserves rigid variables beneath nested universals") {
      val source = """
        |instance choose[A, B]: A -> B -> A = (a: A, b: B) => a
        |def specialize[T](x: T): T = {
        |  let selected = summon[forall B. T -> B -> T]
        |  selected[String](x, "ignored")
        |}
        |specialize[Int](21)
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("SMT synthesis supports impredicative type arguments") {
      val polymorphic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val wanted = Type.Arrow(polymorphic, polymorphic)
      val found = new SmtSynthesizer().synthesize(wanted, SynthesisContext(List(polymorphic), 0))
      assert(success(found) == ImplicitTerm.TypeApp(ImplicitTerm.Instance(0), polymorphic))
    }

    test("SMT synthesis can apply an implicit function exposed by an inferred type argument") {
      val source = """
        |(implicit produce: forall A. (A -> Int) ~> A,
        | implicit handler: (String ~> Bool) -> Int,
        | implicit text: String) => summon[Bool]
        |""".stripMargin
      val compilation = success(Pipeline.compile(source))
      assert(success(TypeChecker.infer(compilation.target)) == compilation.elaborated.tpe.erased)
    }

    test("SMT synthesis can instantiate a universal exposed after implicit application") {
      val source = """
        |(implicit produce: forall A. (A -> Int) ~> A,
        | implicit handler: (forall B. B -> B) -> Int) => summon[Int -> Int]
        |""".stripMargin
      val compilation = success(Pipeline.compile(source))
      assert(success(TypeChecker.infer(compilation.target)) == compilation.elaborated.tpe.erased)
    }

    test("SMT synthesis stops at ordinary arrows exposed during construction") {
      val ordinary = Type.Arrow(Type.StringType, Type.BoolType)
      val polymorphic = Type.Forall(Type.Implicit(Type.Arrow(Type.Variable(0), Type.IntType), Type.Variable(0)))
      val cases = Vector(
        (Type.IntType, List(Type.Forall(Type.Arrow(Type.Variable(0), Type.IntType)), Type.StringType), 4),
        (Type.BoolType, List(polymorphic, Type.Arrow(ordinary, Type.IntType), Type.StringType), 6),
        (Type.BoolType, List(
          Type.Implicit(Type.StringType, Type.Arrow(Type.IntType, Type.BoolType)), Type.StringType, Type.IntType
        ), 5)
      )
      cases.foreach { case (goal, instances, steps) =>
        val synthesizer = new SmtSynthesizer(SynthesisLimits(maximumSteps = steps))
        expectFailure(synthesizer.synthesize(goal, SynthesisContext(instances, 0))) {
          case Error.SynthesisLimit(actual, limit, _) => actual == goal && limit == steps
          case _ => false
        }
      }
    }

    test("SMT synthesis does not unify distinct rigid type variables") {
      expectFailure(new SmtSynthesizer().synthesize(Type.Variable(1), SynthesisContext(List(Type.Variable(0)), 2))) {
        case Error.MissingInstance(Type.Variable(1)) => true
        case _ => false
      }
    }

    test("bounded polymorphic failure does not claim uninhabitability") {
      val producer = Type.Forall(Type.Implicit(Type.Variable(0), Type.Variable(0)))
      val synthesizer = new SmtSynthesizer(SynthesisLimits(maximumSteps = 2))
      expectFailure(synthesizer.synthesize(Type.StringType, SynthesisContext(List(producer), 0))) {
        case Error.SynthesisLimit(Type.StringType, 2, _) => true
        case _ => false
      }
    }

    test("SMT synthesis rejects cyclic prerequisites without a seed") {
      val context = SynthesisContext(List(Type.Implicit(Type.IntType, Type.IntType)), 0)
      expectFailure(new SmtSynthesizer().synthesize(Type.IntType, context)) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("SMT search limits are distinct from missing evidence") {
      val context = SynthesisContext(List(Type.Implicit(Type.StringType, Type.IntType), Type.StringType), 0)
      expectFailure(new SmtSynthesizer(SynthesisLimits(maximumSteps = 2)).synthesize(Type.IntType, context)) {
        case Error.SynthesisLimit(Type.IntType, 2, _) => true
        case _ => false
      }
      assert(success(new SmtSynthesizer().synthesize(Type.IntType, context)) ==
        ImplicitTerm.ImplicitApp(ImplicitTerm.Instance(0), ImplicitTerm.Instance(1)))
    }

    test("SMT type-argument depth limits are explicit") {
      val polymorphic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val argument = Type.Arrow(Type.IntType, Type.IntType)
      val wanted = Type.Arrow(argument, argument)
      val synthesizer = new SmtSynthesizer(SynthesisLimits(maximumSteps = 2, maximumTypeDepth = 1))
      expectFailure(synthesizer.synthesize(wanted, SynthesisContext(List(polymorphic), 0))) {
        case Error.SynthesisLimit(actual, 2, 1) => actual == wanted
        case _ => false
      }
    }

    test("SMT queries preserve their caller's context across success and failure") {
      val synthesizer = new SmtSynthesizer()
      val instances = List(Type.Implicit(Type.StringType, Type.IntType), Type.StringType)
      val context = SynthesisContext(instances, 0)
      val first = success(synthesizer.synthesize(Type.IntType, context))
      expectFailure(synthesizer.synthesize(Type.BoolType, context)) {
        case Error.MissingInstance(Type.BoolType) => true
        case _ => false
      }
      assert(success(synthesizer.synthesize(Type.IntType, context)) == first)
      assert(context.instances == instances && context.typeDepth == 0)
    }

    test("SMT input validation rejects ill-scoped types") {
      val synthesizer = new SmtSynthesizer()
      expectFailure(synthesizer.synthesize(Type.Variable(0), SynthesisContext(Nil, 0))) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
      expectFailure(synthesizer.synthesize(Type.IntType, SynthesisContext(List(Type.Variable(1)), 1))) {
        case Error.InvalidTypeIndex(1, 1) => true
        case _ => false
      }
    }

    test("baseline does not instantiate polymorphic implementations") {
      val polymorphic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val wanted = Type.Arrow(Type.IntType, Type.IntType)
      val context = SynthesisContext(List(polymorphic), typeDepth = 0)
      expectFailure(new NaiveSynthesizer().synthesize(wanted, context)) {
        case Error.MissingInstance(actual) if actual == wanted => true
        case _ => false
      }
    }

    test("baseline does not compose available implementations") {
      val function = Type.Implicit(Type.StringType, Type.IntType)
      val context = SynthesisContext(List(function, Type.StringType), typeDepth = 0)
      expectFailure(new NaiveSynthesizer().synthesize(Type.IntType, context)) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("baseline only selects the nearest exact implementation") {
      val context = SynthesisContext(List(Type.IntType, Type.IntType), typeDepth = 0)
      val found = new NaiveSynthesizer().synthesize(Type.IntType, context)
      assert(success(found) == ImplicitTerm.Instance(0))
    }

    test("a replacement synthesizer can compose existing first-class values") {
      val composing = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          val function = context.instances.indexOf(Type.Implicit(Type.IntType, Type.IntType))
          val argument = context.instances.indexOf(Type.IntType)
          if goal == Type.IntType && function >= 0 && argument >= 0 then {
            Result.Ok(ImplicitTerm.ImplicitApp(ImplicitTerm.Instance(function), ImplicitTerm.Instance(argument)))
          } else {
            new NaiveSynthesizer().synthesize(goal, context)
          }
        }
      }
      val source = "instance f: Int ~> Int = (implicit n: Int) => n + 1 in instance n: Int = 21 in summon[Int]"
      assert(success(Pipeline.run(source, composing)) == Value.Integer(22))
    }

    test("the resolver rejects ordinary applications anywhere in synthesized evidence") {
      val application = ImplicitTerm.App(ImplicitTerm.Instance(1), ImplicitTerm.Instance(2))
      val constructions = Vector(application, ImplicitTerm.ImplicitApp(ImplicitTerm.Instance(0), application))
      val source = """
        |instance number: Int = 21
        |instance render: Int -> String = (number: Int) => "value"
        |instance wrap: String ~> String = (implicit text: String) => "(" ++ text ++ ")"
        |summon[String]
        |""".stripMargin
      constructions.foreach { construction =>
        val invalid = new Synthesizer {
          def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = Result.Ok(construction)
        }
        expectFailure(Pipeline.compile(source, invalid)) {
          case Error.InvalidEvidenceForm(found) => found == application
          case _ => false
        }
      }
    }

    test("implicit evidence application requires an implicit arrow before erasure") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.ImplicitApp(ImplicitTerm.Instance(0), ImplicitTerm.Instance(1)))
        }
      }
      val source = """
        |instance number: Int = 21
        |instance render: Int -> String = (number: Int) => "value"
        |summon[String]
        |""".stripMargin
      expectFailure(Pipeline.compile(source, invalid)) {
        case Error.ExpectedImplicitFunction(Type.Arrow(Type.IntType, Type.StringType)) => true
        case _ => false
      }
    }

    test("the resolver rejects a synthesizer returning a term of the wrong type") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(0))
        }
      }
      expectFailure(Pipeline.compile("instance n: Int = 0 in summon[String]", invalid)) {
        case Error.InvalidEvidence(Type.StringType, Type.IntType) => true
        case _ => false
      }
    }

    test("the resolver rejects out-of-scope evidence from a synthesizer") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(99))
        }
      }
      expectFailure(Pipeline.compile("summon[Int]", invalid)) {
        case Error.InvalidTermIndex("Δ", 99) => true
        case _ => false
      }
    }

    test("the final checker independently rejects ill-typed applications") {
      val malformed = Term.App(Term.Lam(Type.IntType, Term.Var(0)), Term.StringLit("no"))
      expectFailure(TypeChecker.infer(malformed)) { case Error.ExpectedType(_, _) => true; case _ => false }
    }

    test("the final checker rejects free type variables and unerased implicit types") {
      expectFailure(TypeChecker.infer(Term.Lam(Type.Variable(0), Term.Var(0)))) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
      expectFailure(TypeChecker.infer(Term.Lam(Type.Implicit(Type.IntType, Type.IntType), Term.Var(0)))) {
        case Error.ImplicitTypeInTarget(_) => true
        case _ => false
      }
    }

    test("the core checker infers and checks explicit terms independently of the surface language") {
      val fields = Map(
        "next" -> Term.Lam(Type.IntType, Term.BinOp(OpKind.Add, Term.Var(0), Term.IntLit(1))),
        "enabled" -> Term.BoolLit(true),
        "label" -> Term.StringLit("count")
      )
      val recordType = Type.Record(Map(
        "next" -> Type.Arrow(Type.IntType, Type.IntType), "enabled" -> Type.BoolType, "label" -> Type.StringType
      ))
      assert(success(TypeChecker.infer(Term.Record(fields))) == recordType)
      assert(success(TypeChecker.check(Term.Record(fields), recordType)) == ())
      val term = Term.Let(Term.Record(fields), Term.App(Term.Proj(Term.Var(0), "next"), Term.IntLit(20)))
      assert(success(TypeChecker.infer(term)) == Type.IntType)
      assert(success(TypeChecker.check(term, Type.IntType)) == ())
      assert(success(Evaluator.evaluate(term)) == Value.Integer(21))
      expectFailure(TypeChecker.check(term, Type.StringType)) {
        case Error.ExpectedType(Type.StringType, Type.IntType) => true
        case _ => false
      }
      val fewerFields = Type.Record(Map("next" -> Type.Arrow(Type.IntType, Type.IntType)))
      expectFailure(TypeChecker.check(Term.Record(fields), fewerFields)) {
        case Error.ExpectedType(expected, actual) => expected == fewerFields && actual == recordType
        case _ => false
      }
    }

    test("the core checker validates negative depths and every input environment binding") {
      expectFailure(TypeChecker.infer(Term.IntLit(21), typeDepth = -1)) {
        case Error.InvalidTypeDepth(-1) => true
        case _ => false
      }
      expectFailure(TypeChecker.check(Term.IntLit(21), Type.IntType, typeDepth = -1)) {
        case Error.InvalidTypeDepth(-1) => true
        case _ => false
      }
      val implicitType = Type.Implicit(Type.IntType, Type.IntType)
      Vector(
        Type.Variable(0) -> Error.InvalidTypeIndex(0, 0),
        Type.Forall(Type.Variable(1)) -> Error.InvalidTypeIndex(1, 1),
        Type.Record(Map("f" -> implicitType)) -> Error.ImplicitTypeInTarget(implicitType)
      ).foreach { case (invalid, error) =>
        val environment = List(Type.IntType, invalid)
        assert(TypeChecker.infer(Term.IntLit(21), environment) == Result.Err(error))
        assert(TypeChecker.check(Term.IntLit(21), Type.IntType, environment) == Result.Err(error))
      }
    }

    test("the core checker validates expected types before comparing them") {
      val implicitType = Type.Implicit(Type.IntType, Type.IntType)
      Vector(
        Type.Variable(-1) -> Error.InvalidTypeIndex(-1, 0),
        Type.Forall(Type.Variable(1)) -> Error.InvalidTypeIndex(1, 1),
        implicitType -> Error.ImplicitTypeInTarget(implicitType)
      ).foreach { case (expected, error) =>
        assert(TypeChecker.check(Term.IntLit(21), expected) == Result.Err(error))
      }
      assert(success(TypeChecker.check(Term.Var(0), Type.Variable(0), List(Type.Variable(0)), typeDepth = 1)) == ())
    }

    test("core type abstraction weakens the entire open environment without changing the caller's context") {
      val environment = List(Type.Arrow(Type.Variable(0), Type.Variable(0)), Type.Variable(0))
      val term = Term.TypeLam(Term.App(Term.Var(0), Term.Var(1)))
      val expected = Type.Forall(Type.Variable(1))
      assert(success(TypeChecker.infer(term, environment, typeDepth = 1)) == expected)
      assert(success(TypeChecker.check(term, expected, environment, typeDepth = 1)) == ())
      assert(TypeChecker.infer(Term.TypeLam(Term.Var(2)), environment, typeDepth = 1) ==
        Result.Err(Error.InvalidTermIndex("target", 2)))
      assert(success(TypeChecker.infer(Term.Var(0), environment, typeDepth = 1)) == environment.head)
      assert(environment == List(Type.Arrow(Type.Variable(0), Type.Variable(0)), Type.Variable(0)))
      val nested = Term.TypeLam(Term.Lam(Type.Variable(0), Term.TypeLam(Term.Var(0))))
      assert(success(TypeChecker.infer(nested)) ==
        Type.Forall(Type.Arrow(Type.Variable(0), Type.Forall(Type.Variable(1)))))
    }

    test("core type application supports impredicative arguments and capture-avoiding substitution") {
      val identity = Term.TypeLam(Term.Lam(Type.Variable(0), Term.Var(0)))
      val identityType = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val application = Term.App(Term.TypeApp(identity, identityType), identity)
      assert(success(TypeChecker.infer(application)) == identityType)
      assert(success(TypeChecker.check(application, identityType)) == ())
      val constant = Term.TypeLam(Term.TypeLam(Term.Lam(Type.Variable(1), Term.Var(0))))
      val open = Term.TypeApp(constant, Type.Variable(0))
      assert(success(TypeChecker.infer(open, typeDepth = 1)) ==
        Type.Forall(Type.Arrow(Type.Variable(1), Type.Variable(1))))
    }

    test("the core checker reports malformed elimination forms and nonrecursive let scope") {
      Vector(
        Term.Var(-1) -> Error.InvalidTermIndex("target", -1),
        Term.Var(0) -> Error.InvalidTermIndex("target", 0),
        Term.App(Term.IntLit(0), Term.IntLit(1)) -> Error.ExpectedFunction(Type.IntType),
        Term.TypeApp(Term.IntLit(0), Type.IntType) -> Error.ExpectedPolymorphic(Type.IntType),
        Term.Proj(Term.IntLit(0), "value") -> Error.ExpectedRecord(Type.IntType),
        Term.Proj(Term.Record(Map.empty), "value") -> Error.MissingField("value"),
        Term.Let(Term.Var(0), Term.IntLit(1)) -> Error.InvalidTermIndex("target", 0),
        Term.BinOp(OpKind.Add, Term.IntLit(1), Term.BoolLit(true)) -> Error.ExpectedType(Type.IntType, Type.BoolType)
      ).foreach { case (term, error) => assert(TypeChecker.infer(term) == Result.Err(error)) }
    }

    test("resolution independently checks the result against its claimed core type") {
      val invalid = Elaborated(ImplicitTerm.IntLit(21), Type.BoolType)
      assert(new implicitlang.resolution.Resolver().resolve(invalid).run(new NaiveSynthesizer()).result ==
        Result.Err(Error.ExpectedType(Type.BoolType, Type.IntType)))
    }

    test("program parsing retains ordered declarations separately from its final expression") {
      val source = "trait C[A] = { run: A -> A } in def id(x: Int): Int = x in instance i: Int = id(21) in summon[Int]"
      val ast = success(new Parser().parse(source))
      ast match {
        case Program(
          Vector(
            Declaration.Trait("C", Vector("A"), _),
            Declaration.Def("id", _, _, _, _),
            Declaration.Instance("i", _, _, _)
          ),
          SurfaceExpr.Need(SurfaceType.IntType)
        ) => ()
        case other => throw new AssertionError(s"Unexpected declaration lowering: $other")
      }
      assert(run(source) == Value.Integer(21))
    }

    test("where elaborates to ordinary implicit arrows in the calculus") {
      val source = "trait C[A] = { run: A -> A } in def f[A where C[A]](x: A): A = x in f"
      val checked = success(new Elaborator().elaborate(success(new Parser().parse(source))))
      checked.tpe match {
        case Type.Forall(Type.Implicit(Type.Record(_), Type.Arrow(Type.Variable(0), Type.Variable(0)))) => ()
        case other => throw new AssertionError(s"Unexpected qualified type: $other")
      }
    }

    test("named instance values remain usable through ordinary function arguments") {
      val source = "def id(x: Int): Int = x in instance answer: Int = 21 in id(answer)"
      assert(run(source) == Value.Integer(21))
    }

    test("type synonyms do not need an extra runtime declaration") {
      val source = "type N = Int in instance answer: N = 21 in summon[Int]"
      assert(run(source) == Value.Integer(21))
    }

    test("typed field projection takes precedence over implicit method sugar") {
      val source = """
        |type Person = { name: String }
        |instance getter: { name: Person -> String } = { name = (p: Person) => "unused" };
        |{ name = "Ada" }.name
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("raw selection accepts any receiver without classifying it as a record") {
      assert(expression("21.show") == SurfaceExpr.Select(SurfaceExpr.IntLit(21), "show"))
      assert(expression("make(21).value") == SurfaceExpr.Select(
        SurfaceExpr.App(SurfaceExpr.Var("make"), SurfaceExpr.IntLit(21)), "value"
      ))
      assert(expression("((x: Int) => x).run") == SurfaceExpr.Select(
        SurfaceExpr.Lam("x", Some(SurfaceType.IntType), SurfaceExpr.Var("x")),
        "run"
      ))
      val expected = SurfaceExpr.App(
        SurfaceExpr.QualifiedSelect(
          SurfaceType.Named("ToString", Vector(SurfaceType.Named("Person", Vector.empty))), "toString"
        ),
        SurfaceExpr.Var("x")
      )
      assert(expression("ToString[Person]::toString(x)") == expected)
      assert(expression("ToString [Person] :: toString x") == expected)
      assert(expression("summon[ToString[Person]].toString(x)") == SurfaceExpr.App(
        SurfaceExpr.Select(
          SurfaceExpr.Need(SurfaceType.Named("ToString", Vector(SurfaceType.Named("Person", Vector.empty)))), "toString"
        ),
        SurfaceExpr.Var("x")
      ))
    }

    test("elaboration classifies record fields and methods while leaving evidence requests unresolved") {
      val field = success(new Elaborator().elaborate(parse("{ value = 21 }.value")))
      assert(field == Elaborated(
        ImplicitTerm.Proj(ImplicitTerm.Record(Map("value" -> ImplicitTerm.IntLit(21))), "value"),
        Type.IntType
      ))
      val methodType = Type.Record(Map("inc" -> Type.Arrow(Type.IntType, Type.IntType)))
      val method = success(new Elaborator().elaborate(parse("(implicit methods: { inc: Int -> Int }) => 21.inc")))
      assert(method.expression == ImplicitTerm.ImplicitLam(
        methodType,
        ImplicitTerm.App(
          ImplicitTerm.Proj(ImplicitTerm.Need(methodType, Some("methods")), "inc"), ImplicitTerm.IntLit(21)
        ),
        Some("methods")
      ))
      val qualified = "type C = { inc: Int -> Int } in C::inc"
      val selected = success(new Elaborator().elaborate(parse(qualified)))
      assert(selected.expression == ImplicitTerm.Proj(ImplicitTerm.Need(methodType), "inc"))
      expectFailure(Pipeline.compile(qualified)) {
        case Error.MissingInstance(actual) => actual == methodType
        case _ => false
      }
    }

    test("type names cannot be used as values or dotted receivers even when evidence is available") {
      val prefix = "trait C[A] = { run: A -> A } in implement C[Int] = { run = (n: Int) => n } in "
      Vector("C", "C[Int]", "C[Int].run", "C[Int].run(21)").foreach { expression =>
        expectFailure(Pipeline.compile(prefix + expression)) {
          case Error.UnknownTerm("C") => true
          case _ => false
        }
      }
      assert(run(prefix + "C[Int]::run(21)") == Value.Integer(21))
      assert(run(prefix + "summon[{ run: Int -> Int }].run(21)") == Value.Integer(21))
    }

    test("qualified selection uses type names while dotted selection uses value names") {
      val source = """
        |type C = { value: Int }
        |instance selected: C = { value = 21 }
        |let C = { value = 99 }
        |{ qualified = C::value, ordinary = C.value }
        |""".stripMargin
      assert(run(source) == Value.Record(Map("ordinary" -> Value.Integer(99), "qualified" -> Value.Integer(21))))
      expectFailure(Pipeline.compile("let x = { value = 21 } in x::value")) {
        case Error.UnknownType("x") => true
        case _ => false
      }
      val polymorphic = "type C = Int in let C = [A] => { value = 21 } in C[String].value"
      assert(run(polymorphic) == Value.Integer(21))
    }

    test("record receivers with no matching field can use implementation methods") {
      val source = """
        |type Person = { name: String }
        |trait ToString[A] = { toString: A -> String }
        |implement ToString[Person] = { toString = (p: Person) => p.name }
        |{ name = "Ada" }.toString
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("selection on a function expression can resolve an implementation method") {
      val source = """
        |type Function = Int -> Int
        |instance methods: { run: Function -> Int } = { run = (f: Function) => f(20) }
        |((x: Int) => x + 1).run
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("a present record field wins even when calling it is invalid") {
      val source = """
        |type Person = { show: Int }
        |instance methods: { show: Person -> (Int -> Int) } = { show = (p: Person) => (n: Int) => n }
        |{ show = 21 }.show(1)
        |""".stripMargin
      expectFailure(Pipeline.compile(source)) {
        case Error.ExpectedFunction(Type.IntType) => true
        case _ => false
      }
    }

    test("explicit type qualification disambiguates method providers") {
      val prefix = """
        |type First = { show: Int -> String }
        |type Second = { show: Int -> String, tag: String }
        |instance first: First = { show = (n: Int) => "first" }
        |instance second: Second = { show = (n: Int) => "second", tag = "tag" }
        |""".stripMargin
      expectFailure(Pipeline.compile(prefix + "21.show")) {
        case Error.AmbiguousMethod("show", _) => true
        case _ => false
      }
      assert(run(prefix + "First::show(21)") == Value.Text("first"))
      assert(run(prefix + "Second::show(21)") == Value.Text("second"))
    }

    test("receiver syntax and explicit class selection have the same meaning") {
      val prefix = "trait C[A] = { value: A -> Int } in implement C[Int] = { value = (n: Int) => n + 1 } in "
      assert(run(prefix + "21.value") == Value.Integer(22))
      assert(run(prefix + "C[Int]::value(21)") == Value.Integer(22))
    }

    test("method sugar rejects multiple distinct candidate evidence types") {
      val source = """
        |instance first: { show: Int -> String } = { show = (n: Int) => "first" }
        |instance second: { show: Int -> String, tag: String } = { show = (n: Int) => "second", tag = "tag" }
        |21.show
        |""".stripMargin
      expectFailure(Pipeline.compile(source)) { case Error.AmbiguousMethod("show", _) => true; case _ => false }
    }

    test("constrained function bodies are checked against their declared result type") {
      val source = "def f(x: Int): String = x in f(21)"
      expectFailure(Pipeline.compile(source)) { case Error.ExpectedType(_, _) => true; case _ => false }
    }

    test("where accepts trait conjunctions separated by and") {
      val source = """
        |trait C[A] = { run: A -> A }
        |trait D = { value: Int }
        |def f[A where C[A] and D](x: A): A = C[A]::run(x)
        |implement D = { value = 0 }
        |implement C[String] = { run = (s: String) => s }
        |f[String]("Ada")
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("where rejects commas and ordinary types across type parameter clauses") {
      Vector("Int", "{ value: Int }", "C[A], C[A]").foreach { constraint =>
        Vector(
          s"def f[A where $constraint](x: A): A = x in f",
          s"instance f[A where $constraint]: A -> A = (x: A) => x in f",
          s"implement[A where $constraint] C[A] = { value = 0 } in 0"
        ).foreach { source =>
          expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
        }
      }
      Vector("A", "Alias").foreach { constraint =>
        expectFailure(Pipeline.compile(
          s"type Alias = { value: Int } in def f[A where $constraint](x: A): A = x in f"
        )) { case Error.ExpectedTrait(`constraint`) => true; case _ => false }
      }
    }

    test("traits cannot appear in ordinary type positions or explicit evidence annotations") {
      val prefix = "trait C = { value: Int } in "
      Vector(
        "let c: C = { value = 21 } in c",
        "(c: C) => c",
        "(implicit c: C) => c",
        "def f(x: Int): C = { value = x } in f",
        "instance c: C = { value = 21 } in c",
        "type Alias = C in 0",
        "type Container = { field: C } in 0",
        "type Producer = Int ~> C in 0",
        "type Quantified = forall A. C in 0",
        "summon[C]",
        "([A] => (x: A) => x)[C]",
        "trait Nested[A] = { value: A } in def f[where Nested[C]](x: Int): Int = x in f"
      ).foreach { source =>
        expectFailure(Pipeline.compile(prefix + source)) { case Error.TraitInType("C") => true; case _ => false }
      }
    }

    test("traits require record definitions and capture their lexical type environment") {
      Vector("Int", "A", "A -> A", "forall B. B -> B").foreach { definition =>
        expectFailure(Pipeline.compile(s"trait C[A] = $definition in 0")) {
          case Error.ExpectedRecord(_) => true
          case _ => false
        }
      }
      val source = """
        |type Saved = String
        |type Dictionary[A] = { original: Saved, value: A }
        |trait C[A] = Dictionary[A]
        |type Saved = Int
        |implement C[Int] = { value = 21, original = "Ada" }
        |C[Int]::original
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("trait expansion preserves captured type variables beneath type parameter clauses") {
      val source = """
        |trait Captured[A, T] = { value: A, original: T }
        |def get[B, T where Captured[B, T]](ignored: B): T = Captured[B, T]::original
        |def outer[T](value: T): T = get[Int, T](using { value = 0, original = value })(0)
        |outer[String]("Ada")
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("trait references check declaration kind arity and shadowing") {
      expectFailure(Pipeline.compile("type C = { value: Int } in implement C = { value = 21 } in 0")) {
        case Error.ExpectedTrait("C") => true
        case _ => false
      }
      expectFailure(Pipeline.compile("trait C[A] = { value: A } in def f[where C](x: Int): Int = x in f")) {
        case Error.TypeArity("C", 1, 0) => true
        case _ => false
      }
      expectFailure(Pipeline.compile("def f[where Missing](x: Int): Int = x in f")) {
        case Error.UnknownTrait("Missing") => true
        case _ => false
      }
      expectFailure(Pipeline.compile("trait C = {} in def f[C where C](x: C): C = x in f")) {
        case Error.ExpectedTrait("C") => true
        case _ => false
      }
      assert(run("trait C = {} in type C = Int in let n: C = 21 in n") == Value.Integer(21))
    }

    test("implement is confined to the outer declaration sequence") {
      val declaration = "implement C = { value = 21 } in C::value"
      val prefix = "trait C = { value: Int } in "
      Vector(
        s"{ $declaration }",
        s"($declaration)",
        s"(x: Int) => $declaration",
        s"def f(x: Int): Int = { $declaration } in f(0)",
        s"let x = { $declaration } in x",
        s"{ value = { $declaration } }",
        s"((x: Int) => x)({ $declaration })",
        s"instance x: Int = { $declaration } in x"
      ).foreach { source =>
        expectFailure(new Parser().parse(prefix + source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
      assert(run(prefix + "def id(x: Int): Int = x in " +
        "implement C = { let n = 21 in given saved: Int = n in { value = id(saved) } } in C::value") ==
        Value.Integer(21))
    }

    test("implement accepts computed dictionaries checks their types and is nonrecursive") {
      val prefix = "trait C = { value: Int } in "
      Vector(
        "def chosen: { value: Int } = { value = 21 } in implement C = chosen in C::value",
        "def make(x: Int): { value: Int } = { value = x } in implement C = make(21) in C::value"
      ).foreach(source => assert(run(prefix + source) == Value.Integer(21)))
      expectFailure(Pipeline.compile(prefix + "implement C = { value = true } in 0")) {
        case Error.ExpectedType(Type.IntType, Type.BoolType) => true
        case _ => false
      }
      expectFailure(Pipeline.compile(prefix + "implement C = { value = C::value } in C::value")) {
        case Error.MissingInstance(Type.Record(fields)) => fields == Map("value" -> Type.IntType)
        case _ => false
      }
    }

    test("trait restrictions leave structural evidence and explicit dictionaries in the calculus") {
      val source = """
        |trait First = { value: Int }
        |trait Second = { value: Int }
        |implement First = { value = 21 }
        |def read[where Second](ignored: Int): Int = Second::value
        |{ found = read(0), explicit = read(using { value = 42 })(0) }
        |""".stripMargin
      assert(run(source) == Value.Record(Map("found" -> Value.Integer(21), "explicit" -> Value.Integer(42))))
    }

    test("record maps compare structurally throughout parsing checking and evaluation") {
      val first = "{ z = 21, a = true, q = 3, d = 4, b = 5 }"
      val second = "{ b = 5, d = 4, q = 3, a = true, z = 21 }"
      assert(parse(first) == parse(second))
      val left = success(Pipeline.compile(first))
      val right = success(Pipeline.compile(second))
      assert(left.elaborated == right.elaborated && left.target == right.target)
      assert(left.elaborated.hashCode == right.elaborated.hashCode)
      assert(run(first) == run(second))
      Vector("type R = { x: Int, x: Bool } in 0", "trait R = { x: Int, x: Int } in 0").foreach { source =>
        expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
      }
    }

    test("SMT record encoding and decoded type arguments ignore map insertion order") {
      val fields = Vector("z" -> Type.IntType, "a" -> Type.StringType, "q" -> Type.BoolType)
      val first = Type.Record(fields.toMap)
      val second = Type.Record(fields.reverse.toMap)
      val identity = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val context = SynthesisContext(List(identity), 0)
      val found = success(new SmtSynthesizer().synthesize(Type.Arrow(first, second), context))
      assert(found == ImplicitTerm.TypeApp(ImplicitTerm.Instance(0), first))
      assert(success(first.validate(0)) == ())
    }

    test("record type substitution preserves labels and free variable scope") {
      val body = Type.Record(Map("z" -> Type.Variable(0), "a" -> Type.Variable(1)))
      assert(body.instantiate(Type.StringType) == Type.Record(Map(
        "a" -> Type.Variable(0), "z" -> Type.StringType
      )))
    }

    test("a synthesized term cannot introduce an arbitrary literal") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.IntLit(21))
        }
      }
      expectFailure(Pipeline.compile("summon[Int]", invalid)) {
        case Error.InvalidEvidenceForm(ImplicitTerm.IntLit(_)) => true
        case _ => false
      }
    }

    test("a synthesized term cannot read an ordinary variable from Γ") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Var(0))
        }
      }
      expectFailure(Pipeline.compile("let x = 21 in summon[Int]", invalid)) {
        case Error.InvalidEvidenceForm(ImplicitTerm.Var(0)) => true
        case _ => false
      }
    }

    test("synthesis validation distinguishes arrow kinds even when their erasures agree") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(0))
        }
      }
      expectFailure(Pipeline.compile("instance f: Int -> Int = (n: Int) => n in summon[Int ~> Int]", invalid)) {
        case Error.InvalidEvidence(_, _) => true
        case _ => false
      }
    }

    test("a replacement synthesizer can explicitly instantiate and apply a polymorphic producer") {
      val composing = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          if goal == Type.StringType then {
            val producer = context.instances.indexWhere {
              case Type.Forall(Type.Implicit(Type.Variable(0), Type.Variable(0))) => true
              case _ => false
            }
            val argument = context.instances.indexOf(Type.StringType)
            Result.Ok(ImplicitTerm.ImplicitApp(
              ImplicitTerm.TypeApp(ImplicitTerm.Instance(producer), Type.StringType),
              ImplicitTerm.Instance(argument)
            ))
          } else {
            new NaiveSynthesizer().synthesize(goal, context)
          }
        }
      }
      val source = """
        |instance id: forall A. A ~> A = [A] => (implicit x: A) => x
        |instance text: String = "Ada"
        |summon[String]
        |""".stripMargin
      assert(success(Pipeline.run(source, composing)) == Value.Text("Ada"))
    }

    test("implementation values can be stored in and returned from ordinary records") {
      assert(run("instance n: Int = 21 in let box = { value = n } in box.value") == Value.Integer(21))
    }

    test("a type parameter shadowing an alias does not become a term reference") {
      expectFailure(Pipeline.compile("type C = Int in [C] => C")) {
        case Error.UnknownTerm("C") => true
        case _ => false
      }
    }

    test("type trait implement instance and def declarations are top-level only") {
      Vector(
        "type Number = Int in 21",
        "trait C = { value: Int } in 21",
        "implement C = { value = 21 } in 21",
        "def identity(x: Int): Int = x in identity(21)",
        "instance answer: Int = 21 in answer"
      ).foreach { declaration =>
        Vector(
          s"{ $declaration }",
          s"($declaration)",
          s"let result = { $declaration } in result",
          s"let result = 0 in $declaration",
          s"given result: Int = 0 in $declaration",
          s"def outer(x: Int): Int = { $declaration } in outer(0)",
          s"(x: Int) => $declaration",
          s"{ value = { $declaration } }"
        ).foreach { source =>
          expectFailure(new Parser().parse(source)) { case Error.Parse(_, _, _) => true; case _ => false }
        }
      }
      val local = expression("{ let x = 20 in given y: Int = x + 1 in summon[Int] }")
      local match {
        case SurfaceExpr.Let("x", _, SurfaceExpr.Given("y", _, _, SurfaceExpr.Need(SurfaceType.IntType))) => ()
        case other => throw new AssertionError(s"Unexpected binding expression: $other")
      }
      assert(run("{ let x = 20 in given y: Int = x + 1 in summon[Int] }") == Value.Integer(21))
    }

    test("recursive definitions become a single let-bound fixpoint") {
      val source = "def run(step: (Int -> Int) -> Int): Int = " +
        "step((value: Int) => run((ignored: Int -> Int) => value)) in run"
      val compilation = success(Pipeline.compile(source))
      compilation.elaborated.expression match {
        case ImplicitTerm.Let(annotation, ImplicitTerm.Fix(recursiveType, _, _), ImplicitTerm.Var(0), _) => {
          assert(annotation == recursiveType && annotation == compilation.elaborated.tpe)
        }
        case other => throw new AssertionError(s"Expected a recursive definition: $other")
      }
      assert(run(source + "((again: Int -> Int) => again(21))") == Value.Integer(21))
    }

    test("nonrecursive definitions including ordinary values omit fixpoints") {
      Vector(
        "def x: Int = 21 in x",
        "def r: { l: Int } = { l = 21 } in r.l",
        "def f(x: Int): Int = x in f(21)"
      ).foreach { source =>
        success(Pipeline.compile(source)).elaborated.expression match {
          case ImplicitTerm.Let(_, ImplicitTerm.Fix(_, _, _), _, _) => throw new AssertionError("Unexpected fixpoint")
          case ImplicitTerm.Let(_, _, _, _) => ()
          case other => throw new AssertionError(s"Expected a definition binding: $other")
        }
        assert(run(source) == Value.Integer(21))
      }
    }

    test("recursion detection respects parameters lambdas local bindings and field labels") {
      Vector(
        "def f(f: Int): Int = f in f(21)",
        "def f: Int -> Int = (f: Int) => f in f(21)",
        "def x: Int = { let x = 21 in x } in x",
        "def x: Int = { given x: Int = 21 in x } in x",
        "def x: Int = { x = 21 }.x in x"
      ).foreach { source =>
        assert(run(source) == Value.Integer(21))
        success(Pipeline.compile(source)).target match {
          case Term.Let(Term.Fix(_, _), _) => throw new AssertionError("A shadowed name was treated as recursion")
          case _ => ()
        }
      }
      expectFailure(Pipeline.compile("def x: Int = let x = x in x in 0")) {
        case Error.RecursiveDefinitionType("x", Type.IntType) => true
        case _ => false
      }
    }

    test("recursive definition restrictions inspect the resolved type beneath quantifiers") {
      Vector(
        "def x: Int = x in 0",
        "def x[A]: Int = x[A] in 0",
        "type Data = { l: Int } in def x: Data = { l = x.l + 1 } in 0"
      ).foreach { source =>
        expectFailure(Pipeline.compile(source)) { case Error.RecursiveDefinitionType("x", _) => true; case _ => false }
      }
      val source = """
        |type Worker = ((Int -> Int) -> Int) -> Int
        |def run: Worker = (step: (Int -> Int) -> Int) =>
        |  step((value: Int) => run((ignored: Int -> Int) => value))
        |run((again: Int -> Int) => again(21))
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
      val implicitSource = source.replace("((Int -> Int) -> Int) -> Int", "((Int -> Int) -> Int) ~> Int")
        .replace("(step:", "(implicit step:").replace("run((ignored:", "run(using (ignored:")
        .replace("run((again:", "run(using (again:")
      assert(run(implicitSource) == Value.Integer(21))
    }

    test("recursive closures retain lexical implementations and outer ordinary values") {
      val source = """
        |trait Offset = { offset: Int }
        |def base: Int = 20
        |implement Offset = { offset = 1 }
        |def run(step: (Int -> Int) -> Int): Int =
        |  step((value: Int) => run((ignored: Int -> Int) => value + Offset::offset))
        |implement Offset = { offset = 100 }
        |run((again: Int -> Int) => again(base))
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("core fixpoints accept ordinary values and recursive records") {
      val constant = Term.Fix(Type.IntType, Term.IntLit(21))
      assert(success(TypeChecker.infer(constant)) == Type.IntType)
      assert(success(Evaluator.evaluate(constant)) == Value.Integer(21))
      assert(success(TypeChecker.infer(Term.Fix(Type.IntType, Term.Var(0)))) == Type.IntType)
      val recordType = Type.Record(Map("read" -> Type.Arrow(Type.IntType, Type.IntType)))
      val recursive = Term.Fix(recordType, Term.Record(Map(
        "read" -> Term.Lam(Type.IntType, Term.Let(Term.Var(1), Term.Var(1)))
      )))
      val application = Term.App(Term.Proj(recursive, "read"), Term.IntLit(21))
      assert(success(TypeChecker.infer(application)) == Type.IntType)
      assert(success(Evaluator.evaluate(application)) == Value.Integer(21))
      val snapshot = success(Evaluator.evaluate(recursive))
      assert(success(Evaluator.evaluate(application)) == Value.Integer(21))
      assert(success(Evaluator.evaluate(recursive)) == snapshot)
    }

    test("fixpoint checking rejects mismatched bodies and malformed annotations") {
      expectFailure(TypeChecker.infer(Term.Fix(Type.IntType, Term.BoolLit(true)))) {
        case Error.ExpectedType(Type.IntType, Type.BoolType) => true
        case _ => false
      }
      expectFailure(TypeChecker.infer(Term.Fix(Type.Variable(0), Term.Var(0)))) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
      val malformed = Type.Implicit(Type.IntType, Type.IntType)
      expectFailure(TypeChecker.infer(Term.Fix(malformed, Term.Var(0)))) {
        case Error.ImplicitTypeInTarget(_) => true
        case _ => false
      }
    }

    test("fixpoint binders participate in term weakening and type shifting") {
      val term = Term.Fix(Type.Variable(0), Term.App(Term.Var(0), Term.Var(1)))
      assert(term.shiftTerms(1) == Term.Fix(Type.Variable(0), Term.App(Term.Var(0), Term.Var(2))))
      assert(term.shiftTypes(1) == Term.Fix(Type.Variable(1), Term.App(Term.Var(0), Term.Var(1))))
    }

    test("fixpoints cannot be invented by an evidence synthesizer") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Fix(Type.IntType, ImplicitTerm.IntLit(21)))
        }
      }
      expectFailure(Pipeline.compile("summon[Int]", invalid)) {
        case Error.InvalidEvidenceForm(ImplicitTerm.Fix(_, _, _)) => true
        case _ => false
      }
    }

    test("evaluation uses explicit continuations for deeply nested bindings") {
      val term = (0 until 20000).foldLeft[Term](Term.IntLit(21)) { (body, _) => Term.Let(Term.IntLit(0), body) }
      assert(success(Evaluator.evaluate(term)) == Value.Integer(21))
      assert(success(Evaluator.evaluate(Term.Fix(Type.IntType, term))) == Value.Integer(21))
    }

    test("ordinary closures capture value environments independently of their callers") {
      val body = Term.BinOp(OpKind.Add, Term.Var(1), Term.Var(0))
      val closure = success(Evaluator.evaluate(Term.Lam(Type.IntType, body), List(Value.Integer(20))))
      val application = Term.App(Term.Var(0), Term.IntLit(1))
      assert(success(Evaluator.evaluate(application, List(closure, Value.Integer(1000)))) == Value.Integer(21))
      assert(success(Evaluator.evaluate(application, List(closure, Value.Integer(-1000)))) == Value.Integer(21))
    }

    test("fixpoint unfolding preserves outer values beneath nested binders and recursive references") {
      val functionType = Type.Arrow(Type.IntType, Type.IntType)
      val recordType = Type.Record(Map("read" -> functionType))
      val body = Term.Record(Map(
        "read" -> Term.Lam(Type.IntType, Term.Let(Term.Var(1), Term.BinOp(OpKind.Add, Term.Var(3), Term.Var(1))))
      ))
      val fix = Term.Fix(recordType, body)
      val application = Term.App(Term.Proj(fix, "read"), Term.IntLit(1))
      assert(success(TypeChecker.check(application, Type.IntType, List(Type.IntType))) == ())
      assert(success(Evaluator.evaluate(application, List(Value.Integer(20)))) == Value.Integer(21))
      val unfolded = body.instantiate(fix)
      assert(success(TypeChecker.check(unfolded, recordType, List(Type.IntType))) == ())
      val unfoldedApplication = Term.App(Term.Proj(unfolded, "read"), Term.IntLit(1))
      assert(success(Evaluator.evaluate(unfoldedApplication, List(Value.Integer(20)))) == Value.Integer(21))
    }

    test("term substitution avoids both term and type variable capture") {
      val argument = Term.Lam(Type.Variable(0), Term.Var(1))
      val body = Term.TypeLam(Term.Lam(Type.Variable(0), Term.Var(1)))
      val substituted = body.instantiate(argument)
      assert(substituted == Term.TypeLam(Term.Lam(Type.Variable(0), Term.Lam(Type.Variable(1), Term.Var(2)))))
      val expected = Type.Forall(Type.Arrow(Type.Variable(0), Type.Arrow(Type.Variable(1), Type.StringType)))
      assert(success(TypeChecker.check(substituted, expected, List(Type.StringType), typeDepth = 1)) == ())
      assert(Term.Let(Term.Var(0), Term.Var(1)).instantiate(Term.Var(0)) == Term.Let(Term.Var(0), Term.Var(1)))
      assert(Term.Fix(Type.IntType, Term.Var(1)).instantiate(Term.Var(0)) == Term.Fix(Type.IntType, Term.Var(1)))
      assert(Term.Record(Map("bound" -> Term.Var(0), "outer" -> Term.Var(1))).instantiate(Term.IntLit(21)) ==
        Term.Record(Map("bound" -> Term.IntLit(21), "outer" -> Term.Var(0))))
    }

    test("implicit names preserve spelling and freshen shadowed binders without changing core identity") {
      import implicitlang.`implicit`.ImplicitPrinter
      val first = success(Pipeline.compile("(x: Int) => (x: Int) => x"))
      val renamed = success(Pipeline.compile("(outer: Int) => (inner: Int) => inner"))
      assert(first.target == renamed.target)
      val nested = ImplicitTerm.Lam(
        Type.IntType, ImplicitTerm.Lam(Type.IntType, ImplicitTerm.Var(1), Some("x")), Some("x")
      )
      assert(ImplicitPrinter.term(nested) == "λ(x: Int). λ(x1: Int). x")
      val mixed = ImplicitTerm.ImplicitLam(
        Type.IntType, ImplicitTerm.Lam(Type.IntType, ImplicitTerm.Instance(0), Some("value")), Some("value")
      )
      assert(ImplicitPrinter.term(mixed) == "λ⟨value: Int⟩. λ(value1: Int). value")
      val abstracted = success(Compiler.prepare("def id[A](value: A): A = value in id"))
      val rendered = ImplicitPrinter.term(abstracted.elaborated.expression)
      assert(rendered.contains("let id:") && rendered.contains("ΛA. λ(value: A). value"))
    }

    test("trait evidence has fresh implementation names while named instances retain their spelling") {
      import implicitlang.`implicit`.ImplicitPrinter
      val prepared = success(Compiler.prepare(
        """trait Show[A] = { show: A -> String }
          |instance imp_Show: Int = 21
          |implement Show[String] = { show = (text: String) => text }
          |def display[A where Show[A]](value: A): String = Show[A]::show(value)
          |given local: Int = imp_Show
          |display[String]("Ada")""".stripMargin
      ))
      val rendered = ImplicitPrinter.term(prepared.elaborated.expression)
      assert(rendered.startsWith("instance imp_Show: Int = 21 in\n"))
      assert(rendered.contains("instance imp_Show1: {show: String → String}"))
      assert(rendered.contains("ΛA. λ⟨imp_Show2: {show: A → String}⟩. λ(value: A)."))
      assert(rendered.contains("instance local: Int = imp_Show in\n"))
      val report = prepared.resolve.run(new SmtSynthesizer())
      assert(report.goals.head.request.names.instances == List("imp_Show2", "imp_Show1", "imp_Show"))
      assert(report.goals.last.request.names.instances == List("local", "imp_Show1", "imp_Show"))
      assert(success(Evaluator.evaluate(success(report.result).target)) == Value.Text("Ada"))
    }

    test("resolution reports retain lexical names and completed goals when a later goal fails") {
      val prepared = success(Compiler.prepare(
        "instance seed: Int = 21 in let saved = summon[Int] in summon[String]"
      ))
      val report = prepared.resolve.run(new SmtSynthesizer())
      assert(report.goals.size == 2)
      assert(report.goals.head.request.names.instances == List("seed"))
      assert(report.goals(1).request.names.ordinary == List("saved"))
      assert(report.goals.head.validation == Result.Ok(()))
      assert(report.goals(1).validation == Result.Err(Error.MissingInstance(Type.StringType)))
      expectFailure(report.result) {
        case Error.MissingInstance(Type.StringType) => true
        case _ => false
      }
      val shadowed = success(Compiler.prepare("instance item: Int = 1 in given item: Int = 2 in summon[Int]"))
        .resolve.run(new SmtSynthesizer())
      assert(shadowed.goals.head.request.names.instances == List("item1", "item"))
      assert(success(Evaluator.evaluate(success(shadowed.result).target)) == Value.Integer(2))
    }

    test("the functional SMT wrapper preserves retained problems and decodes exact model values") {
      import implicitlang.smt.*
      import implicitlang.smt.Smt.*
      val number = SmtConstant("number", SmtSort.Int)
      val expected = BigInt("123456789012345678901234567890")
      val problem = SmtProblem(
        Vector.empty, Vector.empty, Vector(number),
        Vector(SmtConstraint("given", number === int(expected))), Vector(SmtObservation("number", number))
      )
      val solver = new SmtNativeSolver
      def answer(problem: SmtProblem[String]): SmtAnswer = {
        success(solver.check(problem, 1000).mapError(failure => Error.SynthesisBackend(failure.cause))).answer
      }
      val first = answer(problem)
      first match {
        case SmtAnswer.Sat(model) => assert(model("number") == SmtValue.Integer(expected))
        case other => throw new AssertionError(s"Expected a model, received $other")
      }
      val contradiction = problem.copy(constraints = problem.constraints :+ SmtConstraint("contradiction", number < int(0)))
      assert(answer(contradiction) == SmtAnswer.Unsat)
      assert(problem.constraints.size == 1 && first == answer(problem))
      assert(!scala.compiletime.testing.typeChecks(
        "import implicitlang.smt.*; import implicitlang.smt.Smt.*; bool(true) + int(1)"
      ))
    }

    test("synthesis reports expose the real bounded queries and a checked evidence program") {
      import implicitlang.smt.*
      val wanted = Type.Record(Map("value" -> Type.IntType))
      val producer = Type.Forall(Type.Implicit(Type.Variable(0), Type.Record(Map("value" -> Type.Variable(0)))))
      val context = SynthesisContext(List(producer, Type.IntType), 0)
      val report = new SmtSynthesizer(SynthesisLimits(maximumSteps = 4)).inspect(wanted, context)
      success(report.result)
      assert(report.attempts.nonEmpty && report.attempts.head.spineCount == 1)
      assert(report.evidence.exists(program => program.cost <= 4 && program.spines.last.result == wanted))
      val accepted = report.attempts.last
      assert(accepted.refinements.isEmpty && accepted.spineCount == 2)
      val replay = success(new SmtNativeSolver().check(accepted.problem, 1000)
        .mapError(failure => Error.SynthesisBackend(failure.cause)))
      assert(replay.answer.isInstanceOf[SmtAnswer.Sat])
      assert(accepted.problem.smtlib(1000).contains("(define-funs-rec"))
      assert(accepted.problem.constraints.exists(_.origin.rule == implicitlang.synthesis.ConstraintRule.ImplicitApplication))
    }

    println(s"$passed passed; $failed failed")
    if failed > 0 then sys.exit(1)
  }
}
