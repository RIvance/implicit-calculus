package implicitlang.surface

import implicitlang.`implicit`.{ImplicitTerm, Elaborated}
import implicitlang.core.Type
import implicitlang.diagnostic.Error
import implicitlang.util.Result

/** Name resolution, alias expansion, checking, and goal insertion. No instance search occurs here. */
final class Elaborator {
  /** Source names follow applications during elaboration and are retained only on the resulting binders and goals. */
  private enum NameHints {
    case Unknown
    case Parameter(name: Option[String], body: NameHints)
    case Fields(values: Map[String, NameHints])

    def parameter: Option[String] = this match {
      case Parameter(name, _) => name
      case _ => None
    }

    def result: NameHints = this match {
      case Parameter(_, body) => body
      case _ => Unknown
    }

    def field(label: String): NameHints = this match {
      case Fields(values) => values.getOrElse(label, Unknown)
      case _ => Unknown
    }
  }

  private final case class NamedTerm(expression: ImplicitTerm, tpe: Type, hints: NameHints = NameHints.Unknown)

  private enum Space {
    case Ordinary, Instance
  }

  private def checkDistinctNames(names: Vector[String]): Result[Unit, Error] = {
    names.groupBy(identity).collectFirst { case (name, occurrences) if occurrences.size > 1 => name } match {
      case Some(name) => Result.Err(Error.DuplicateName(name))
      case None => Result.Ok(())
    }
  }

  private final case class Binding(
    name: String, tpe: Type, space: Space, hints: NameHints, nameHint: Option[String]
  )

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
    def bind(name: String, tpe: Type, space: Space, hints: NameHints = NameHints.Unknown): Scope = {
      val displayName = Option.when(!name.startsWith("@"))(name)
      copy(bindings = Binding(name, tpe, space, hints, displayName) :: bindings)
    }

    def bindType(name: String): Scope = {
      copy(typeNames = name :: typeNames, bindings = bindings.map(binding => binding.copy(tpe = binding.tpe.shift(1))))
    }

    def lookup(name: String): Result[NamedTerm, Error] = {
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
        Result.Ok(NamedTerm(expression, binding.tpe, binding.hints))
      }
    }
  }

  def elaborate(program: Program, expected: Option[Type] = None): Result[Elaborated, Error] = {
    val valid = expected.fold[Result[Unit, Error]](Result.Ok(()))(_.validate(0))
    valid.flatMap(_ => elaborateDeclarations(program.declarations.toList, program.expression, Scope(), expected))
      .map(value => Elaborated(value.expression, value.tpe))
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

  private final case class ResolvedConstraint(name: String, tpe: Type)

  private def instanceName(reference: TraitRef): String = s"imp_${reference.name}"

  private final case class ResolvedTypeParameters(parameters: Vector[String], constraints: Vector[ResolvedConstraint]) {
    def enter(scope: Scope): Scope = {
      val parameterScope = parameters.foldLeft(scope)((current, name) => current.bindType(name))
      constraints.foldLeft(parameterScope) { (current, constraint) =>
        current.bind("@constraint", constraint.tpe, Space.Instance)
      }
    }

    def qualify(tpe: Type): Type = {
      parameters.foldRight(constraints.foldRight(tpe)((constraint, body) => Type.Implicit(constraint.tpe, body))) {
        (_, body) => Type.Forall(body)
      }
    }

    def nameHints(body: NameHints): NameHints = {
      val constrained = constraints.foldRight(body) { (_, result) =>
        NameHints.Parameter(None, result)
      }
      parameters.foldRight(constrained)((_, result) => NameHints.Parameter(None, result))
    }

    /** Λᾱ.λ⟨d₁:D₁⟩.…λ⟨dₙ:Dₙ⟩.c : ∀ᾱ.D₁ ⇒ … ⇒ Dₙ ⇒ A. */
    def abstractValue(value: NamedTerm): NamedTerm = {
      val constrained = constraints.foldRight(value.expression) { (constraint, body) =>
        ImplicitTerm.ImplicitLam(constraint.tpe, body, Some(constraint.name))
      }
      val expression = parameters.foldRight(constrained)((name, body) => ImplicitTerm.TypeLam(body, Some(name)))
      NamedTerm(expression, qualify(value.tpe), nameHints(value.hints))
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

    def abstractValue(value: NamedTerm): NamedTerm = {
      val expression = kind match {
        case ParameterKind.Ordinary => ImplicitTerm.Lam(tpe, value.expression, Some(name))
        case ParameterKind.Implicit => ImplicitTerm.ImplicitLam(tpe, value.expression, Some(name))
      }
      NamedTerm(expression, functionType(value.tpe), NameHints.Parameter(Some(name), value.hints))
    }
  }

  private def resolveTypeParameters(
    typeParameters: TypeParameters, scope: Scope
  ): Result[ResolvedTypeParameters, Error] = {
    for {
      _ <- checkDistinctNames(typeParameters.names)
      parameterScope = typeParameters.names.foldLeft(scope)((current, name) => current.bindType(name))
      constraints <- Result.traverse(typeParameters.constraints) { reference =>
        resolveTrait(reference, parameterScope).map(ResolvedConstraint(instanceName(reference), _))
      }
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
  )(annotation: Scope => Result[Type, Error]): Result[NamedTerm, Error] = {
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

  private def elaborateDefinition(definition: Declaration.Def, scope: Scope): Result[NamedTerm, Error] = {
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
      argumentHints = arguments.foldRight[NameHints](NameHints.Unknown) { (parameter, body) =>
        NameHints.Parameter(Some(parameter.name), body)
      }
      recursiveScope = if recursive then scope.bind(name, signature, Space.Ordinary, resolved.nameHints(argumentHints))
        else scope
      bodyScope = arguments.foldLeft(resolved.enter(recursiveScope))((current, parameter) => parameter.enter(current))
      checked <- elaborate(body, bodyScope, Some(result))
      parameterized = arguments.foldRight(checked)((parameter, value) => parameter.abstractValue(value))
      abstracted = resolved.abstractValue(parameterized)
      value = if recursive then ImplicitTerm.Fix(signature, abstracted.expression, Some(name))
        else abstracted.expression
    } yield NamedTerm(value, signature, abstracted.hints)
  }

  /** Declarations extend the lexical scope in order, then wrap the final expression in term bindings. */
  private def elaborateDeclarations(
    declarations: List[Declaration], body: SurfaceExpr, scope: Scope, expected: Option[Type]
  ): Result[NamedTerm, Error] = {
    def bind(
      name: String, value: NamedTerm, space: Space, rest: List[Declaration], nameHint: Option[String] = None
    ): Result[NamedTerm, Error] = {
      val hint = nameHint.orElse(Option.when(!name.startsWith("@"))(name))
      elaborateDeclarations(rest, body, scope.bind(name, value.tpe, space, value.hints), expected).map { result =>
        val expression = space match {
          case Space.Ordinary => ImplicitTerm.Let(value.tpe, value.expression, result.expression, Some(name))
          case Space.Instance => {
            ImplicitTerm.InstanceLet(value.tpe, value.expression, result.expression, hint)
          }
        }
        NamedTerm(expression, result.tpe, result.hints)
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
          .flatMap(bind("@implementation", _, Space.Instance, rest, Some(instanceName(target))))
      }
      case Declaration.Instance(name, typeParameters, annotation, value) :: rest => {
        elaborateEvidence(typeParameters, value, scope)(resolveType(annotation, _))
          .flatMap(bind(name, _, Space.Instance, rest))
      }
    }
  }

  private def exposeExplicitArrow(value: NamedTerm): NamedTerm = value.tpe match {
    case Type.Implicit(parameter, result) => {
      val hint = value.hints.parameter
      exposeExplicitArrow(NamedTerm(
        ImplicitTerm.ImplicitApp(value.expression, ImplicitTerm.Need(parameter, hint)), result, value.hints.result
      ))
    }
    case _ => value
  }

  /** None synthesizes a type; Some checks the term against an established expected type. */
  private def elaborate(
    expression: SurfaceExpr, scope: Scope, expected: Option[Type]
  ): Result[NamedTerm, Error] = {
    val elaborated: Result[NamedTerm, Error] = expression match {
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
        } yield NamedTerm(
          ImplicitTerm.Lam(parameter, result.expression, Some(name)), Type.Arrow(parameter, result.tpe),
          NameHints.Parameter(Some(name), result.hints)
        )
      }
      /*
       * Γ; Δ, d:A ⊢ e:B ⇝ c
       * ───────────────────────────────── E-Implicit-Lambda
       * Γ; Δ ⊢ λ⟨d:A⟩.e : A ⇒ B ⇝ λ⟨d:A⟩.c
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
        } yield NamedTerm(
          ImplicitTerm.ImplicitLam(parameter, result.expression, Some(name)), Type.Implicit(parameter, result.tpe),
          NameHints.Parameter(Some(name), result.hints)
        )
      }
      case SurfaceExpr.App(function, argument) => {
        elaborate(function, scope, None).map(exposeExplicitArrow).flatMap { prepared =>
          prepared.tpe match {
            case Type.Arrow(parameter, result) => {
              elaborate(argument, scope, Some(parameter)).map { actual =>
                NamedTerm(ImplicitTerm.App(prepared.expression, actual.expression), result, prepared.hints.result)
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
                NamedTerm(
                  ImplicitTerm.ImplicitApp(prepared.expression, actual.expression), result, prepared.hints.result
                )
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
        } yield NamedTerm(
          ImplicitTerm.TypeLam(result.expression, Some(parameter)), Type.Forall(result.tpe),
          NameHints.Parameter(None, result.hints)
        )
      }
      case SurfaceExpr.TypeApp(function, argument) => {
        for {
          prepared <- elaborate(function, scope, None)
          actual <- resolveType(argument, scope)
          result <- prepared.tpe match {
            case Type.Forall(body) => {
              Result.Ok(NamedTerm(
                ImplicitTerm.TypeApp(prepared.expression, actual), body.instantiate(actual), prepared.hints.result
              ))
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
          result <- elaborate(body, scope.bind(name, checked.tpe, Space.Ordinary, checked.hints), expected)
        } yield NamedTerm(
          ImplicitTerm.Let(checked.tpe, checked.expression, result.expression, Some(name)), result.tpe, result.hints
        )
      }
      /*
       * Γ; Δ ⊢ e₁:A ⇝ c₁    Γ; Δ,d:A ⊢ e₂:B ⇝ c₂
       * ─────────────────────────────────────────────────────── E-Instance-Let
       * Γ; Δ ⊢ instance d:A = e₁ in e₂:B ⇝ instance d:A = c₁ in c₂
       */
      case SurfaceExpr.Given(name, annotation, value, body) => {
        for {
          declared <- resolveType(annotation, scope)
          checked <- elaborate(value, scope, Some(declared))
          result <- elaborate(body, scope.bind(name, checked.tpe, Space.Instance, checked.hints), expected)
        } yield NamedTerm(
          ImplicitTerm.InstanceLet(checked.tpe, checked.expression, result.expression, Some(name)),
          result.tpe,
          result.hints
        )
      }
      case SurfaceExpr.Need(annotation) => {
        resolveType(annotation, scope).map(tpe => NamedTerm(ImplicitTerm.Need(tpe), tpe))
      }
      case SurfaceExpr.Ascribe(value, annotation) => {
        for {
          declared <- resolveType(annotation, scope)
          checked <- elaborate(value, scope, Some(declared))
        } yield checked
      }
      case SurfaceExpr.IntLit(value) => Result.Ok(NamedTerm(ImplicitTerm.IntLit(value), Type.IntType))
      case SurfaceExpr.StringLit(value) => Result.Ok(NamedTerm(ImplicitTerm.StringLit(value), Type.StringType))
      case SurfaceExpr.BoolLit(value) => Result.Ok(NamedTerm(ImplicitTerm.BoolLit(value), Type.BoolType))
      case SurfaceExpr.Record(fields) => {
        val fieldTypes = expected.collect { case Type.Record(types) => types }
        val checkedFields = fields.foldLeft[Result[Map[String, NamedTerm], Error]](Result.Ok(Map.empty)) {
          case (accumulated, (label, value)) => {
            for {
              checked <- accumulated
              field <- elaborate(value, scope, fieldTypes.flatMap(_.get(label)))
            } yield checked.updated(label, field)
          }
        }
        checkedFields.map { checked =>
          NamedTerm(
            ImplicitTerm.Record(checked.map { case (label, value) => label -> value.expression }),
            Type.Record(checked.map { case (label, value) => label -> value.tpe }),
            NameHints.Fields(checked.map { case (label, value) => label -> value.hints })
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
          select(NamedTerm(ImplicitTerm.Need(tpe), tpe), label, scope)
        }
      }
      case SurfaceExpr.BinOp(kind, left, right) => {
        for {
          first <- elaborate(left, scope, Some(kind.operandType))
          second <- elaborate(right, scope, Some(kind.operandType))
        } yield NamedTerm(ImplicitTerm.BinOp(kind, first.expression, second.expression), kind.resultType)
      }
    }
    elaborated.flatMap { actual =>
      expected match {
        case Some(tpe) if tpe != actual.tpe => Result.Err(Error.ExpectedType(tpe, actual.tpe))
        case _ => Result.Ok(actual)
      }
    }
  }

  private def select(receiver: NamedTerm, label: String, scope: Scope): Result[NamedTerm, Error] = {
    val field = receiver.tpe match {
      case Type.Record(fields) => fields.get(label)
      case _ => None
    }
    field match {
      case Some(tpe) => {
        Result.Ok(NamedTerm(ImplicitTerm.Proj(receiver.expression, label), tpe, receiver.hints.field(label)))
      }
      case None => elaborateMethod(receiver, label, scope)
    }
  }

  /** Infer a method's evidence TYPE from Δ; defer the actual evidence choice to the synthesizer. */
  private def elaborateMethod(receiver: NamedTerm, label: String, scope: Scope): Result[NamedTerm, Error] = {
    val candidates = scope.bindings.collect {
      case Binding(_, recordType @ Type.Record(fields), Space.Instance, _, _) => {
        fields.get(label).collect {
          case Type.Arrow(parameter, result) if parameter == receiver.tpe => recordType -> result
        }
      }
    }.flatten.distinct
    candidates match {
      case (recordType, result) :: Nil => {
        val binding = scope.bindings.find(value => value.space == Space.Instance && value.tpe == recordType)
        val hint = binding.flatMap(_.nameHint)
        val hints = binding.map(_.hints.field(label).result).getOrElse(NameHints.Unknown)
        val method = ImplicitTerm.Proj(ImplicitTerm.Need(recordType, hint), label)
        Result.Ok(NamedTerm(ImplicitTerm.App(method, receiver.expression), result, hints))
      }
      case Nil => Result.Err(Error.MissingMethod(label, receiver.tpe))
      case _ => Result.Err(Error.AmbiguousMethod(label, candidates.map(_._1).toVector))
    }
  }

}
