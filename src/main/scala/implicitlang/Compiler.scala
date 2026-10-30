package implicitlang

import implicitlang.`implicit`.Elaborated
import implicitlang.core.Term
import implicitlang.diagnostic.Error
import implicitlang.resolution.{Resolution, Resolver}
import implicitlang.surface.{Program, Parser, Elaborator}
import implicitlang.util.Result

final case class Compilation(source: Program, elaborated: Elaborated, target: Term)

final case class PreparedProgram(source: Program, elaborated: Elaborated) {
  def resolve: Resolution[Compilation] = {
    new Resolver().resolve(elaborated).map(target => Compilation(source, elaborated, target))
  }
}

object Compiler {
  def prepare(source: String): Result[PreparedProgram, Error] = {
    for {
      program <- new Parser().parse(source)
      elaborated <- new Elaborator().elaborate(program)
    } yield PreparedProgram(program, elaborated)
  }
}
