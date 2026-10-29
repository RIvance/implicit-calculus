package implicitlang

import implicitlang.syntax.{Expr, TypeExpr, OpKind}
import implicitlang.core.{ImplicitTerm, Type, Term, Elaborated}
import implicitlang.parser.Parser
import implicitlang.elaboration.Elaborator
import implicitlang.synthesis.{Synthesizer, SynthesisContext, NaiveSynthesizer}
import implicitlang.cli.Pipeline
import implicitlang.evaluation.Value

import implicitlang.util.Result

import java.nio.file.{Files, Path}
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

  def main(arguments: Array[String]): Unit = {
    test("parser directly retains a first-class implementation binding") {
      success(new Parser().parse("impl answer: Int = 21 in answer")) match {
        case Expr.Impl("answer", TypeExpr.IntType, Expr.IntLit(value), Expr.Var("answer")) => assert(value == 21)
        case other => throw new AssertionError(s"Unexpected AST: $other")
      }
    }

    test("ordinary and implementation binders have separate indices") {
      val parsed = success(new Parser().parse("impl d: Int = 21 in fun (x: String) => d"))
      val actual = success(new Elaborator().elaborate(parsed)).expression
      assert(actual == ImplicitTerm.Impl(
        Type.IntType,
        ImplicitTerm.IntLit(21),
        ImplicitTerm.Lam(Type.StringType, ImplicitTerm.Instance(0))
      ))
    }

    test("name elaboration inserts a hole without attempting synthesis") {
      val parsed = success(new Parser().parse("summon[Int]"))
      assert(success(new Elaborator().elaborate(parsed)) == Elaborated(ImplicitTerm.Need(Type.IntType), Type.IntType))
      expectFailure(Pipeline.compile("summon[Int]")) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("ordinary application checks its argument") {
      expectFailure(Pipeline.compile("(fun (x: Int) => x)(\"bad\")")) {
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
      assert(run("impl first: Int = 1 in impl second: Int = 2 in summon[Int]") == Value.Integer(2))
    }

    test("instances can be referred to and passed as ordinary values") {
      assert(run("impl value: Int = 21 in (fun (x: Int) => x)(value)") == Value.Integer(21))
    }

    test("an ordinary parameter can be installed as a local implementation") {
      assert(run("(fun (x: Int) => impl chosen: Int = x in summon[Int])(21)") == Value.Integer(21))
    }

    test("implementation bindings are nonrecursive") {
      expectFailure(Pipeline.compile("impl self: Int = summon[Int] in self")) {
        case Error.MissingInstance(Type.IntType) => true
        case _ => false
      }
    }

    test("implementation annotations are checked") {
      expectFailure(Pipeline.compile("impl wrong: Int = \"no\" in wrong")) {
        case Error.ExpectedType(Type.IntType, Type.StringType) => true
        case _ => false
      }
    }

    test("ordinary binders weaken references to the implementation environment") {
      val source = "impl d: Int = 11 in let ignored = \"x\" in (fun (x: String) => summon[Int])(\"y\")"
      assert(run(source) == Value.Integer(11))
    }

    test("implementation binders weaken references to the ordinary environment") {
      val source = "let x = 11 in impl d: String = \"a\" in (fun {q: String} => x){\"b\"}"
      assert(run(source) == Value.Integer(11))
    }

    test("source name shadowing does not remove an older implementation from search") {
      val source = "impl x: Int = 11 in let x = 100 in summon[Int] + x"
      assert(run(source) == Value.Integer(111))
    }

    test("implicit lambdas make their parameters available to synthesis") {
      assert(run("(fun {d: Int} => summon[Int]){21}") == Value.Integer(21))
    }

    test("ordinary function application inserts all leading implicit arguments") {
      val source = "impl d: Int = 21 in (fun {a: Int} => fun {b: Int} => fun (x: Int) => b + x)(1)"
      assert(run(source) == Value.Integer(22))
    }

    test("implicit application is not ordinary application") {
      expectFailure(Pipeline.compile("(fun (x: Int) => x){21}")) {
        case Error.ExpectedImplicitFunction(_) => true
        case _ => false
      }
    }

    test("implicit functions remain first-class when passed through an ordinary arrow") {
      val source = "(fun (f: Int => Int) => f{21})(fun {x: Int} => x)"
      assert(run(source) == Value.Integer(21))
    }

    test("lookup distinguishes implicit and explicit arrows before erasure") {
      val source = "impl f: Int -> Int = fun (x: Int) => x in summon[Int => Int]"
      expectFailure(Pipeline.compile(source)) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("a bare implicit value is not forced merely by variable lookup") {
      val compilation = success(Pipeline.compile("fun {x: Int} => x"))
      assert(compilation.core.tpe == Type.Implicit(Type.IntType, Type.IntType))
      assert(success(compilation.target.infer()) == Type.Arrow(Type.IntType, Type.IntType))
    }

    test("polymorphic identity checks and evaluates") {
      assert(run("(fun [A] => fun (x: A) => x)[Int](21)") == Value.Integer(21))
    }

    test("type abstraction weakens types in both environments") {
      val source = "(fun [A] => fun {d: A} => fun (x: A) => fun [B] => summon[A])[Int]{21}(99)[String]"
      assert(run(source) == Value.Integer(21))
    }

    test("polymorphic type substitution avoids capturing a nested type variable") {
      val body = Type.Forall(Type.Arrow(Type.Variable(1), Type.Variable(0)))
      assert(body.instantiate(Type.StringType) == Type.Forall(Type.Arrow(Type.StringType, Type.Variable(0))))
    }

    test("aliases capture their declaration environment rather than later type names") {
      val source = "(fun [A] => type Saved = A in fun [A] => fun (x: Saved) => x)[Int][String](21)"
      assert(run(source) == Value.Integer(21))
    }

    test("parameterized aliases preserve captured outer type variables") {
      val source = """
        |(fun [A] => type Pair[B] = { first: A, second: B } in
        |  fun [C] => fun (p: Pair[C]) => p.first)[Int][String]({ first = 21, second = "Ada" })
        |""".stripMargin
      assert(run(source) == Value.Integer(21))
    }

    test("record equality does not depend on field order") {
      val source = "(fun (r: { a: Int, b: String }) => r.a)({ b = \"Ada\", a = 21 })"
      assert(run(source) == Value.Integer(21))
    }

    test("duplicate record labels are rejected rather than silently overwritten") {
      expectFailure(Pipeline.compile("{ x = 1, x = 2 }")) { case Error.DuplicateName("x") => true; case _ => false }
    }

    test("alias arity is checked") {
      expectFailure(Pipeline.compile("type Box[A] = { value: A } in summon[Box]")) {
        case Error.AliasArity("Box", 1, 0) => true
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
      val parsed = success(new Parser().parse("1 + 2 * 3"))
      assert(parsed == Expr.BinOp(
        OpKind.Add,
        Expr.IntLit(1),
        Expr.BinOp(OpKind.Mul, Expr.IntLit(2), Expr.IntLit(3))
      ))
      assert(success(new Parser().parse("\"a\" ++ \"b\"")) == Expr.BinOp(
        OpKind.Concat,
        Expr.StringLit("a"),
        Expr.StringLit("b")
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
      assert(success(Term.BinOp(OpKind.Lt, Term.IntLit(1), Term.IntLit(2)).infer()) == Type.BoolType)
      expectFailure(Term.BinOp(OpKind.Add, Term.StringLit("bad"), Term.IntLit(1)).infer()) {
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

    test("operator example combines numeric, boolean, and string values") {
      val fields = Vector(
        "equal" -> Value.Boolean(true),
        "greater" -> Value.Boolean(true),
        "less" -> Value.Boolean(true),
        "message" -> Value.Text("value = three"),
        "sum" -> Value.Integer(3)
      )
      assert(run(Files.readString(Path.of("examples", "operators.imp"))) == Value.Record(fields))
    }

    Vector(
      "display.imp" -> "Student(Ada)",
      "first-class.imp" -> "value = Ada",
      "lexical.imp" -> "outer: Ada / inner: Ada",
      "polymorphic.imp" -> "Ada",
      "explicit-override.imp" -> "explicit: Ada"
    ).foreach { case (file, expected) =>
      test(s"example $file has its specified result") {
        assert(run(Files.readString(Path.of("examples", file))) == Value.Text(expected))
      }
    }

    test("the placeholder rejects a goal requiring polymorphic implementation composition") {
      val source = Files.readString(Path.of("examples", "needs-smt.imp"))
      expectFailure(Pipeline.compile(source)) { case Error.MissingInstance(_) => true; case _ => false }
    }

    test("baseline does not instantiate polymorphic implementations") {
      val generic = Type.Forall(Type.Arrow(Type.Variable(0), Type.Variable(0)))
      val wanted = Type.Arrow(Type.IntType, Type.IntType)
      val context = SynthesisContext(List(generic), typeDepth = 0)
      expectFailure(new NaiveSynthesizer().synthesize(wanted, context)) {
        case Error.MissingInstance(actual) if actual == wanted => true
        case _ => false
      }
    }

    test("baseline does not compose available implementations") {
      val function = Type.Arrow(Type.StringType, Type.IntType)
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
          val function = context.instances.indexOf(Type.Arrow(Type.IntType, Type.IntType))
          val argument = context.instances.indexOf(Type.IntType)
          if goal == Type.IntType && function >= 0 && argument >= 0 then {
            Result.Ok(ImplicitTerm.App(ImplicitTerm.Instance(function), ImplicitTerm.Instance(argument)))
          } else {
            new NaiveSynthesizer().synthesize(goal, context)
          }
        }
      }
      val source = "impl f: Int -> Int = fun (n: Int) => n + 1 in impl n: Int = 21 in summon[Int]"
      assert(success(Pipeline.run(source, composing)) == Value.Integer(22))
    }

    test("the resolver rejects a synthesizer returning a term of the wrong type") {
      val invalid = new Synthesizer {
        def synthesize(goal: Type, context: SynthesisContext): Result[ImplicitTerm, Error] = {
          Result.Ok(ImplicitTerm.Instance(0))
        }
      }
      expectFailure(Pipeline.compile("impl n: Int = 0 in summon[String]", invalid)) {
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
      expectFailure(malformed.infer()) { case Error.ExpectedType(_, _) => true; case _ => false }
    }

    test("the final checker rejects free type variables and unerased implicit types") {
      expectFailure(Term.Lam(Type.Variable(0), Term.Var(0)).infer()) {
        case Error.InvalidTypeIndex(0, 0) => true
        case _ => false
      }
      expectFailure(Term.Lam(Type.Implicit(Type.IntType, Type.IntType), Term.Var(0)).infer()) {
        case Error.ImplicitTypeInTarget(_) => true
        case _ => false
      }
    }

    test("declaration syntax parses straight to type, ordinary, and implementation bindings") {
      val source = "trait C[A] = { run: A -> A } def id(x: Int): Int = x impl i: Int = id(21) summon[Int]"
      val ast = success(new Parser().parse(source))
      ast match {
        case Expr.TypeLet("C", Vector("A"), _, Expr.Let("id", _, Expr.Impl("i", _, _, _))) => ()
        case other => throw new AssertionError(s"Unexpected declaration lowering: $other")
      }
      assert(run(source) == Value.Integer(21))
    }

    test("where becomes an implicit lambda without a new declaration representation") {
      val source = "trait C[A] = { run: A -> A } def f[A where C[A]](x: A): A = x f"
      val checked = success(new Elaborator().elaborate(success(new Parser().parse(source))))
      checked.tpe match {
        case Type.Forall(Type.Implicit(Type.Record(_), Type.Arrow(Type.Variable(0), Type.Variable(0)))) => ()
        case other => throw new AssertionError(s"Unexpected qualified type: $other")
      }
    }

    test("named instance values remain usable through ordinary function arguments") {
      val source = "impl answer: Int = 21 def id(x: Int): Int = x id(answer)"
      assert(run(source) == Value.Integer(21))
    }

    test("type synonyms do not need an extra runtime declaration") {
      val source = "type N = Int impl answer: N = 21 summon[Int]"
      assert(run(source) == Value.Integer(21))
    }

    test("typed field projection takes precedence over implicit method sugar") {
      val source = """
        |type Person = { name: String }
        |impl getter: { name: Person -> String } = { name = (p: Person) => "unused" };
        |{ name = "Ada" }.name
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
    }

    test("receiver syntax and explicit class selection have the same meaning") {
      val prefix = "trait C[A] = { value: A -> Int } impl C[Int] { value = (n: Int) => n + 1 } "
      assert(run(prefix + "21.value") == Value.Integer(22))
      assert(run(prefix + "C[Int].value(21)") == Value.Integer(22))
    }

    test("method sugar rejects multiple distinct candidate evidence types") {
      val source = """
        |impl first: { show: Int -> String } = { show = (n: Int) => "first" }
        |impl second: { show: Int -> String, tag: String } = { show = (n: Int) => "second", tag = "tag" }
        |21.show
        |""".stripMargin
      expectFailure(Pipeline.compile(source)) { case Error.AmbiguousMethod("show", _) => true; case _ => false }
    }

    test("constrained function bodies are checked against their declared result type") {
      val source = "def f(x: Int): String = x f(21)"
      expectFailure(Pipeline.compile(source)) { case Error.ExpectedType(_, _) => true; case _ => false }
    }

    test("generic where clauses allow both comma and and constraint separators") {
      val source = """
        |trait C[A] = { run: A -> A }
        |def f[A where C[A], Int](x: A): A = C[A].run(x)
        |impl i: Int = 0
        |impl C[String] { run = (s: String) => s }
        |f[String]("Ada")
        |""".stripMargin
      assert(run(source) == Value.Text("Ada"))
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
      expectFailure(Pipeline.compile("val x = 21 summon[Int]", invalid)) {
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
      expectFailure(Pipeline.compile("impl f: Int -> Int = (n: Int) => n summon[Int => Int]", invalid)) {
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
      val source = "impl id: forall A. A => A = fun [A] => fun {x: A} => x impl text: String = \"Ada\" summon[String]"
      assert(success(Pipeline.run(source, composing)) == Value.Text("Ada"))
    }

    test("implementation values can be stored in and returned from ordinary records") {
      assert(run("impl n: Int = 21 val box = { value = n } box.value") == Value.Integer(21))
    }

    test("a type parameter shadowing an alias does not become a term reference") {
      expectFailure(Pipeline.compile("type C = Int in fun [C] => C")) {
        case Error.UnknownTerm("C") => true
        case _ => false
      }
    }

    println(s"$passed passed; $failed failed")
    if failed > 0 then sys.exit(1)
  }
}
