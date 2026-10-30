package implicitlang.cli

import implicitlang.Error
import implicitlang.syntax.Expr
import implicitlang.core.{Term, Elaborated}
import implicitlang.parser.Parser
import implicitlang.elaboration.Elaborator
import implicitlang.synthesis.{Synthesizer, SmtSynthesizer}
import implicitlang.resolution.Resolver
import implicitlang.evaluation.{Evaluator, Value}

import implicitlang.util.Result

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

final case class Compilation(source: Expr, core: Elaborated, target: Term)

object Pipeline {
  def compile(source: String, synthesizer: Synthesizer = new SmtSynthesizer): Result[Compilation, Error] = {
    for {
      expression <- new Parser().parse(source)
      checked <- new Elaborator().elaborate(expression)
      target <- new Resolver(synthesizer).resolve(checked)
    } yield Compilation(expression, checked, target)
  }

  def run(source: String, synthesizer: Synthesizer = new SmtSynthesizer): Result[Value, Error] = {
    compile(source, synthesizer).flatMap(compilation => Evaluator.evaluate(compilation.target))
  }
}

object Main {
  def main(arguments: Array[String]): Unit = {
    if arguments.isEmpty || arguments.length > 2 || (arguments.length == 2 && arguments(1) != "--dump") then {
      System.err.println("Usage: sbt 'runMain implicitlang.cli.Main examples/display.imp [--dump]'")
      sys.exit(2)
    }
    try {
      val source = Files.readString(Path.of(arguments(0)))
      Pipeline.compile(source) match {
        case Result.Err(error) => {
          System.err.println(error)
          sys.exit(1)
        }
        case Result.Ok(compilation) => {
          if arguments.contains("--dump") then {
            println(s"First AST: ${compilation.source}")
            println(s"Implicit term: ${compilation.core.expression}")
            println(s"Implicit term type: ${compilation.core.tpe.show}")
            println(s"Target: ${compilation.target}")
          }
          Evaluator.evaluate(compilation.target) match {
            case Result.Ok(value) => println(value.show)
            case Result.Err(error) => {
              System.err.println(error)
              sys.exit(1)
            }
          }
        }
      }
    } catch {
      case NonFatal(error) => {
        System.err.println(s"I/O or runtime failure: ${error.getMessage}")
        sys.exit(1)
      }
    }
  }
}
