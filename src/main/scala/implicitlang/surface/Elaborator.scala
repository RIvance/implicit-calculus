package implicitlang.surface

import implicitlang.`implicit`.{ImplicitTerm, Elaborated}
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.util.Result

/** Name resolution, alias expansion, checking, and goal insertion. No instance search occurs here. */
final class Elaborator {
  private enum Space {
    case Ordinary, Instance
  }

  private def checkDistinctNames(names: Vector[String]): Result[Unit, Error] = {
    names.groupBy(identity).collectFirst { case (name, occurrences) if occurrences.size > 1 => name } match {
      case Some(name) => Result.Err(Error.DuplicateName(name))
      case None => Result.Ok(())
    }
  }

  private final case class Binding(name: String, tpe: Type, space: Space)

  private enum DeclarationKind {
    case Alias, Trait
  }

  private final case class TypeDeclaration(kind: DeclarationKind, arity: Int, body: Type) {
    def instantiate(arguments: Vector[Type]): Type = {
      val quantified = (0 until arity).foldLeft(body)((body, _) => Type.Forall(body))
      arguments.foldLeft(quantified) { (current, argument) =>
        current match {
          case Type.Forall(body) => body.instantiate(argument)
          case _ => throw new IllegalStateException("Type declaration arity invariant violated")
        }
      }
    }
  }

  private final case class Scope(
    typeNames: List[String] = Nil,
    bindings: List[Binding] = Nil,
    declarations: Map[String, TypeDeclaration] = Map.empty
  ) {
    def bind(name: String, tpe: Type, space: Space): Scope = {
      copy(bindings = Binding(name, tpe, space) :: bindings)
    }

    def bindType(name: String): Scope = {
      copy(typeNames = name :: typeNames, bindings = bindings.map(binding => binding.copy(tpe = binding.tpe.shift(1))))
    }

    def lookup(name: String): Result[Elaborated, Error] = {
      val position = bindings.indexWhere(_.name == name)
      if position < 0 then {
        Result.Err(Error.UnknownTerm(name))
      } else {
        val binding = bindings(position)
        val index = bindings.take(position).count(_.space == binding.space)
        val expression = binding.space match {
          case Space.Ordinary => ImplicitTerm.Var(index)
          case Space.Instance => ImplicitTerm.Instance(index)
        }
        Result.Ok(Elaborated(expression, binding.tpe))
      }
    }
  }

  def elaborate(program: Program, expected: Option[Type] = None): Result[Elaborated, Error] = {
    val valid = expected.fold[Result[Unit, Error]](Result.Ok(()))(_.validate(0))
    valid.flatMap(_ => elaborateDeclarations(program.declarations.toList, program.expression, Scope(), expected))
  }

  private def resolveType(expression: SurfaceType, scope: Scope): Result[Type, Error] = expression match {
    case SurfaceType.IntType => Result.Ok(Type.IntType)
    case SurfaceType.StringType => Result.Ok(Type.StringType)
    case SurfaceType.BoolType => Result.Ok(Type.BoolType)
    case SurfaceType.Named(name, arguments) => {
      val index = scope.typeNames.indexOf(name)
      if index >= 0 then {
        if arguments.isEmpty then Result.Ok(Type.Variable(index)) else Result.Err(Error.TypeVariableApplied(name))
      } else {
        scope.declarations.get(name) match {
          case None => Result.Err(Error.UnknownType(name))
          case Some(declaration) if declaration.kind == DeclarationKind.Trait => Result.Err(Error.TraitInType(name))
          case Some(declaration) => instantiate(name, declaration, arguments, scope)
        }
      }
    }
    case SurfaceType.Arrow(parameter, result) => {
      for {
        from <- resolveType(parameter, scope)
        to <- resolveType(result, scope)
      } yield Type.Arrow(from, to)
    }
    case SurfaceType.Implicit(parameter, result) => {
      for {
        from <- resolveType(parameter, scope)
        to <- resolveType(result, scope)
      } yield Type.Implicit(from, to)
    }
    case SurfaceType.Forall(parameter, body) => resolveType(body, scope.bindType(parameter)).map(Type.Forall.apply)
    case SurfaceType.Record(fields) => {
      Result.traverse(fields)(resolveType(_, scope)).map(Type.Record.apply)
    }
  }

  private def instantiate(
    name: String, declaration: TypeDeclaration, arguments: Vector[SurfaceType], scope: Scope
  ): Result[Type, Error] = {
    if declaration.arity != arguments.size then {
      Result.Err(Error.TypeArity(name, declaration.arity, arguments.size))
    } else {
      Result.traverse(arguments)(resolveType(_, scope)).map(declaration.instantiate)
    }
  }

  private def resolveTrait(reference: TraitRef, scope: Scope): Result[Type, Error] = {
    if scope.typeNames.contains(reference.name) then Result.Err(Error.ExpectedTrait(reference.name))
    else scope.declarations.get(reference.name) match {
      case None => Result.Err(Error.UnknownTrait(reference.name))
      case Some(declaration) if declaration.kind != DeclarationKind.Trait => {
        Result.Err(Error.ExpectedTrait(reference.name))
      }
      case Some(declaration) => instantiate(reference.name, declaration, reference.arguments, scope)
    }
  }

  private def resolveSelectionType(annotation: SurfaceType, scope: Scope): Result[Type, Error] = annotation match {
    case SurfaceType.Named(name, arguments)
        if !scope.typeNames.contains(name) && scope.declarations.get(name).exists(_.kind == DeclarationKind.Trait) => {
      resolveTrait(TraitRef(name, arguments), scope)
    }
    case _ => resolveType(annotation, scope)
  }

  private final case class ResolvedTypeParameters(parameters: Vector[String], constraints: Vector[Type]) {
    def enter(scope: Scope): Scope = {
      val parameterScope = parameters.foldLeft(scope)((current, name) => current.bindType(name))
      constraints.foldLeft(parameterScope)((current, tpe) => current.bind("@constraint", tpe, Space.Instance))
    }

    def qualify(tpe: Type): Type = {
      parameters.foldRight(constraints.foldRight(tpe)(Type.Implicit.apply))((_, body) => Type.Forall(body))
    }

    /** Λᾱ.λ{d₁:D₁}.…λ{dₙ:Dₙ}.c : ∀ᾱ.D₁ ⇒ … ⇒ Dₙ ⇒ A. */
    def abstractValue(value: Elaborated): Elaborated = {
      val constrained = constraints.foldRight(value.expression)(ImplicitTerm.ImplicitLam.apply)
      val expression = parameters.foldRight(constrained)((_, body) => ImplicitTerm.TypeLam(body))
      Elaborated(expression, qualify(value.tpe))
    }
  }

  private final case class ResolvedParameter(name: String, tpe: Type, kind: ParameterKind) {
    def enter(scope: Scope): Scope = kind match {
      case ParameterKind.Ordinary => scope.bind(name, tpe, Space.Ordinary)
      case ParameterKind.Implicit => scope.bind(name, tpe, Space.Instance)
    }

    def functionType(result: Type): Type = kind match {
      case ParameterKind.Ordinary => Type.Arrow(tpe, result)
      case ParameterKind.Implicit => Type.Implicit(tpe, result)
    }

    def abstractValue(value: Elaborated): Elaborated = {
      val expression = kind match {
        case ParameterKind.Ordinary => ImplicitTerm.Lam(tpe, value.expression)
        case ParameterKind.Implicit => ImplicitTerm.ImplicitLam(tpe, value.expression)
      }
      Elaborated(expression, functionType(value.tpe))
    }
  }

  private def resolveTypeParameters(
    typeParameters: TypeParameters, scope: Scope
  ): Result[ResolvedTypeParameters, Error] = {
    for {
      _ <- checkDistinctNames(typeParameters.names)
      parameterScope = typeParameters.names.foldLeft(scope)((current, name) => current.bindType(name))
      constraints <- Result.traverse(typeParameters.constraints)(resolveTrait(_, parameterScope))
    } yield ResolvedTypeParameters(typeParameters.names, constraints)
  }

  private def declareType(
    kind: DeclarationKind, name: String, parameters: Vector[String], definition: SurfaceType, scope: Scope
  ): Result[Scope, Error] = {
    for {
      _ <- checkDistinctNames(parameters)
      parameterScope = parameters.foldLeft(scope)((current, parameter) => current.bindType(parameter))
      template <- resolveType(definition, parameterScope)
      _ <- (kind, template) match {
        case (DeclarationKind.Trait, Type.Record(_)) | (DeclarationKind.Alias, _) => Result.Ok(())
        case _ => Result.Err(Error.ExpectedRecord(template))
      }
      declaration = TypeDeclaration(kind, parameters.size, template)
    } yield scope.copy(declarations = scope.declarations.updated(name, declaration))
  }

  private def elaborateEvidence(
    typeParameters: TypeParameters, value: SurfaceExpr, scope: Scope
  )(annotation: Scope => Result[Type, Error]): Result[Elaborated, Error] = {
    for {
      resolved <- resolveTypeParameters(typeParameters, scope)
      current = resolved.enter(scope)
      declared <- annotation(current)
      checked <- elaborate(value, current, Some(declared))
    } yield resolved.abstractValue(checked)
  }

  private def permitsRecursion(tpe: Type): Boolean = tpe match {
    case Type.Forall(body) => permitsRecursion(body)
    case Type.Arrow(_, _) | Type.Implicit(_, _) => true
    case _ => false
  }

  private def elaborateDefinition(definition: Declaration.Def, scope: Scope): Result[Elaborated, Error] = {
    val Declaration.Def(name, typeParameters, parameters, resultType, body) = definition
    val recursive = !parameters.exists(_.name == name) && body.refersTo(name)
    for {
      resolved <- resolveTypeParameters(typeParameters, scope)
      parameterScope = resolved.enter(scope)
      arguments <- Result.traverse(parameters) { parameter =>
        resolveType(parameter.annotation, parameterScope).map(ResolvedParameter(parameter.name, _, parameter.kind))
      }
      result <- resolveType(resultType, parameterScope)
      signature = resolved.qualify(arguments.foldRight(result)((parameter, tpe) => parameter.functionType(tpe)))
      _ <- if !recursive || permitsRecursion(signature) then Result.Ok(())
        else Result.Err(Error.RecursiveDefinitionType(name, signature))
      recursiveScope = if recursive then scope.bind(name, signature, Space.Ordinary) else scope
      bodyScope = arguments.foldLeft(resolved.enter(recursiveScope))((current, parameter) => parameter.enter(current))
      checked <- elaborate(body, bodyScope, Some(result))
      parameterized = arguments.foldRight(checked)((parameter, value) => parameter.abstractValue(value))
      abstracted = resolved.abstractValue(parameterized)
      value = if recursive then ImplicitTerm.Fix(signature, abstracted.expression) else abstracted.expression
    } yield Elaborated(value, signature)
  }

  /** Declarations extend the lexical scope in order, then wrap the final expression in term bindings. */
  private def elaborateDeclarations(
    declarations: List[Declaration], body: SurfaceExpr, scope: Scope, expected: Option[Type]
  ): Result[Elaborated, Error] = {
    def bind(name: String, value: Elaborated, space: Space, rest: List[Declaration]): Result[Elaborated, Error] = {
      elaborateDeclarations(rest, body, scope.bind(name, value.tpe, space), expected).map { result =>
        val expression = space match {
          case Space.Ordinary => ImplicitTerm.Let(value.tpe, value.expression, result.expression)
          case Space.Instance => ImplicitTerm.Impl(value.tpe, value.expression, result.expression)
        }
        Elaborated(expression, result.tpe)
      }
    }
    declarations match {
      case Nil => elaborate(body, scope, expected)
      case Declaration.TypeAlias(name, parameters, definition) :: rest => {
        declareType(DeclarationKind.Alias, name, parameters, definition, scope)
          .flatMap(elaborateDeclarations(rest, body, _, expected))
      }
      case Declaration.Trait(name, parameters, definition) :: rest => {
        declareType(DeclarationKind.Trait, name, parameters, definition, scope)
          .flatMap(elaborateDeclarations(rest, body, _, expected))
      }
      case (definition: Declaration.Def) :: rest => {
        elaborateDefinition(definition, scope).flatMap(bind(definition.name, _, Space.Ordinary, rest))
      }
      case Declaration.Implement(typeParameters, target, value) :: rest => {
        elaborateEvidence(typeParameters, value, scope)(resolveTrait(target, _))
          .flatMap(bind("@implementation", _, Space.Instance, rest))
      }
      case Declaration.Instance(name, typeParameters, annotation, value) :: rest => {
        elaborateEvidence(typeParameters, value, scope)(resolveType(annotation, _))
          .flatMap(bind(name, _, Space.Instance, rest))
      }
    }
  }

  private def exposeExplicitArrow(value: Elaborated): Elaborated = value.tpe match {
    case Type.Implicit(parameter, result) => {
      exposeExplicitArrow(Elaborated(ImplicitTerm.ImplicitApp(value.expression, ImplicitTerm.Need(parameter)), result))
    }
    case _ => value
  }

  /** None synthesizes a type; Some checks the term against an established expected type. */
  private def elaborate(
    expression: SurfaceExpr, scope: Scope, expected: Option[Type]
  ): Result[Elaborated, Error] = {
    val elaborated: Result[Elaborated, Error] = expression match {
      case SurfaceExpr.Var(name) => scope.lookup(name)
      /*
       * Γ,x:A; Δ ⊢ e ⇐ B ⇝ c
       * ───────────────────────────────────── Check-Lambda
       * Γ; Δ ⊢ λx.e ⇐ A → B ⇝ λ(x:A).c
       *
       * Γ,x:A; Δ ⊢ e ⇒ B ⇝ c
       * ───────────────────────────────────── Infer-Lambda
       * Γ; Δ ⊢ λ(x:A).e ⇒ A → B ⇝ λ(x:A).c
       */
      case SurfaceExpr.Lam(name, annotation, body) => {
        for {
          context <- expected match {
            case Some(function: Type.Arrow) => Result.Ok(Some(function))
            case Some(other) => Result.Err(Error.ExpectedFunction(other))
            case None => Result.Ok(None)
          }
          parameter <- annotation match {
            case Some(tpe) => resolveType(tpe, scope)
            case None => context match {
              case Some(function) => Result.Ok(function.parameter)
              case None => Result.Err(Error.MissingParameterType(name))
            }
          }
          _ <- context match {
            case Some(function) if function.parameter != parameter => {
              Result.Err(Error.ExpectedType(function.parameter, parameter))
            }
            case _ => Result.Ok(())
          }
          result <- elaborate(body, scope.bind(name, parameter, Space.Ordinary), context.map(_.result))
        } yield Elaborated(ImplicitTerm.Lam(parameter, result.expression), Type.Arrow(parameter, result.tpe))
      }
      /*
       * Γ; Δ, d:A ⊢ e:B ⇝ c
       * ───────────────────────────────── E-Implicit-Lambda
       * Γ; Δ ⊢ λ{d:A}.e : A ⇒ B ⇝ λ{d:A}.c
       */
      case SurfaceExpr.ImplicitLam(name, annotation, body) => {
        for {
          parameter <- resolveType(annotation, scope)
          resultType <- expected match {
            case Some(Type.Implicit(from, to)) if from == parameter => Result.Ok(Some(to))
            case Some(Type.Implicit(from, _)) => Result.Err(Error.ExpectedType(from, parameter))
            case Some(other) => Result.Err(Error.ExpectedImplicitFunction(other))
            case None => Result.Ok(None)
          }
          result <- elaborate(body, scope.bind(name, parameter, Space.Instance), resultType)
        } yield Elaborated(ImplicitTerm.ImplicitLam(parameter, result.expression), Type.Implicit(parameter, result.tpe))
      }
      case SurfaceExpr.App(function, argument) => {
        elaborate(function, scope, None).map(exposeExplicitArrow).flatMap { prepared =>
          prepared.tpe match {
            case Type.Arrow(parameter, result) => {
              elaborate(argument, scope, Some(parameter)).map { actual =>
                Elaborated(ImplicitTerm.App(prepared.expression, actual.expression), result)
              }
            }
            case other => Result.Err(Error.ExpectedFunction(other))
          }
        }
      }
      case SurfaceExpr.ImplicitApp(function, argument) => {
        elaborate(function, scope, None).flatMap { prepared =>
          prepared.tpe match {
            case Type.Implicit(parameter, result) => {
              elaborate(argument, scope, Some(parameter)).map { actual =>
                Elaborated(ImplicitTerm.ImplicitApp(prepared.expression, actual.expression), result)
              }
            }
            case other => Result.Err(Error.ExpectedImplicitFunction(other))
          }
        }
      }
      case SurfaceExpr.TypeLam(parameter, body) => {
        for {
          bodyType <- expected match {
            case Some(Type.Forall(tpe)) => Result.Ok(Some(tpe))
            case Some(other) => Result.Err(Error.ExpectedPolymorphic(other))
            case None => Result.Ok(None)
          }
          result <- elaborate(body, scope.bindType(parameter), bodyType)
        } yield Elaborated(ImplicitTerm.TypeLam(result.expression), Type.Forall(result.tpe))
      }
      case SurfaceExpr.TypeApp(function, argument) => {
        for {
          prepared <- elaborate(function, scope, None)
          actual <- resolveType(argument, scope)
          result <- prepared.tpe match {
            case Type.Forall(body) => {
              Result.Ok(Elaborated(ImplicitTerm.TypeApp(prepared.expression, actual), body.instantiate(actual)))
            }
            case other => Result.Err(Error.ExpectedPolymorphic(other))
          }
        } yield result
      }
      /*
       * Γ; Δ ⊢ e₁:A ⇝ c₁    Γ,x:A; Δ ⊢ e₂:B ⇝ c₂
       * ───────────────────────────────────────────── E-Let
       * Γ; Δ ⊢ let x = e₁ in e₂:B ⇝ let x:A = c₁ in c₂
       */
      case SurfaceExpr.Let(name, value, body) => {
        for {
          checked <- elaborate(value, scope, None)
          result <- elaborate(body, scope.bind(name, checked.tpe, Space.Ordinary), expected)
        } yield Elaborated(ImplicitTerm.Let(checked.tpe, checked.expression, result.expression), result.tpe)
      }
      /*
       * Γ; Δ ⊢ e₁:A ⇝ c₁    Γ; Δ,d:A ⊢ e₂:B ⇝ c₂
       * ─────────────────────────────────────────────── E-Impl
       * Γ; Δ ⊢ impl d:A = e₁ in e₂:B ⇝ impl d:A = c₁ in c₂
       */
      case SurfaceExpr.Given(name, annotation, value, body) => {
        for {
          declared <- resolveType(annotation, scope)
          checked <- elaborate(value, scope, Some(declared))
          result <- elaborate(body, scope.bind(name, checked.tpe, Space.Instance), expected)
        } yield Elaborated(ImplicitTerm.Impl(checked.tpe, checked.expression, result.expression), result.tpe)
      }
      case SurfaceExpr.Need(annotation) => {
        resolveType(annotation, scope).map(tpe => Elaborated(ImplicitTerm.Need(tpe), tpe))
      }
      case SurfaceExpr.Ascribe(value, annotation) => {
        for {
          declared <- resolveType(annotation, scope)
          checked <- elaborate(value, scope, Some(declared))
        } yield checked
      }
      case SurfaceExpr.IntLit(value) => Result.Ok(Elaborated(ImplicitTerm.IntLit(value), Type.IntType))
      case SurfaceExpr.StringLit(value) => Result.Ok(Elaborated(ImplicitTerm.StringLit(value), Type.StringType))
      case SurfaceExpr.BoolLit(value) => Result.Ok(Elaborated(ImplicitTerm.BoolLit(value), Type.BoolType))
      case SurfaceExpr.Record(fields) => {
        val fieldTypes = expected.collect { case Type.Record(types) => types }
        val checkedFields = fields.foldLeft[Result[Map[String, Elaborated], Error]](Result.Ok(Map.empty)) {
          case (accumulated, (label, value)) => {
            for {
              checked <- accumulated
              field <- elaborate(value, scope, fieldTypes.flatMap(_.get(label)))
            } yield checked.updated(label, field)
          }
        }
        checkedFields.map { checked =>
          Elaborated(
            ImplicitTerm.Record(checked.map { case (label, value) => label -> value.expression }),
            Type.Record(checked.map { case (label, value) => label -> value.tpe })
          )
        }
      }
      /*
       * Γ; Δ ⊢ e:{ℓⱼ:Aⱼ}ⱼ∈J ⇝ c    k ∈ J
       * ────────────────────────────────── E-Project
       * Γ; Δ ⊢ e.ℓₖ:Aₖ ⇝ c.ℓₖ
       *
       * Γ; Δ ⊢ e:A ⇝ c    ℓ ∉ labels(A)    ℳΔ,A,ℓ = {(P,B)}
       * ─────────────────────────────────────────────────── E-Method
       * Γ; Δ ⊢ e.ℓ:B ⇝ (?P).ℓ c
       */
      case SurfaceExpr.Select(receiver, label) => {
        elaborate(receiver, scope, None).flatMap(select(_, label, scope))
      }
      case SurfaceExpr.QualifiedSelect(annotation, label) => {
        resolveSelectionType(annotation, scope).flatMap { tpe =>
          select(Elaborated(ImplicitTerm.Need(tpe), tpe), label, scope)
        }
      }
      case SurfaceExpr.BinOp(kind, left, right) => {
        for {
          first <- elaborate(left, scope, Some(kind.operandType))
          second <- elaborate(right, scope, Some(kind.operandType))
        } yield Elaborated(ImplicitTerm.BinOp(kind, first.expression, second.expression), kind.resultType)
      }
    }
    elaborated.flatMap { actual =>
      expected match {
        case Some(tpe) if tpe != actual.tpe => Result.Err(Error.ExpectedType(tpe, actual.tpe))
        case _ => Result.Ok(actual)
      }
    }
  }

  private def select(receiver: Elaborated, label: String, scope: Scope): Result[Elaborated, Error] = {
    val field = receiver.tpe match {
      case Type.Record(fields) => fields.get(label)
      case _ => None
    }
    field match {
      case Some(tpe) => Result.Ok(Elaborated(ImplicitTerm.Proj(receiver.expression, label), tpe))
      case None => elaborateMethod(receiver, label, scope)
    }
  }

  /** Infer a method's evidence TYPE from Δ; defer the actual evidence choice to the synthesizer. */
  private def elaborateMethod(receiver: Elaborated, label: String, scope: Scope): Result[Elaborated, Error] = {
    val candidates = scope.bindings.collect {
      case Binding(_, recordType @ Type.Record(fields), Space.Instance) => {
        fields.get(label).collect {
          case Type.Arrow(parameter, result) if parameter == receiver.tpe => recordType -> result
        }
      }
    }.flatten.distinct
    candidates match {
      case (recordType, result) :: Nil => {
        val method = ImplicitTerm.Proj(ImplicitTerm.Need(recordType), label)
        Result.Ok(Elaborated(ImplicitTerm.App(method, receiver.expression), result))
      }
      case Nil => Result.Err(Error.MissingMethod(label, receiver.tpe))
      case _ => Result.Err(Error.AmbiguousMethod(label, candidates.map(_._1).toVector))
    }
  }

}
