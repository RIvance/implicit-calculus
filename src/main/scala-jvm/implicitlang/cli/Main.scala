package implicitlang.cli

import implicitlang.{Compiler, Compilation}
import implicitlang.`implicit`.ImplicitPrinter
import implicitlang.core.{Evaluator, Value}
import implicitlang.diagnostic.Error
import implicitlang.synthesis.{Synthesizer, SmtSynthesizer}
import implicitlang.util.Result

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

object Pipeline {
  def compile(source: String, synthesizer: Synthesizer = new SmtSynthesizer): Result[Compilation, Error] = {
    Compiler.prepare(source).flatMap(_.resolve.run(synthesizer).result)
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
            println(s"Implicit term: ${ImplicitPrinter.term(compilation.elaborated.expression)}")
            println(s"Implicit term type: ${ImplicitPrinter.tpe(compilation.elaborated.tpe)}")
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
