# Scala Style Guide

This guide defines conventions for Scala 3 code that is visually consistent,
semantically explicit, and easy to maintain. Match a more specific local
convention when a component deliberately establishes one, but do not mix
formatting styles within the same component.

Names, packages, and directory layouts in the examples are illustrative. The
examples use the self-contained `Result` definition in section 11.

## 1. Core principles

- Use braces for declarations and multiline method bodies. Do not replace
  those braces with significant-indentation syntax.
- Combine object-oriented ownership with functional transformations.
- Prefer immutable domain values and explicit state transitions.
- Represent closed alternatives with typed algebraic data types.
- Use structured errors at the point where failures originate.
- Prefer complete names over uncommon abbreviations.
- Keep short code compact and long code deliberately multiline.
- Treat 120 columns as the soft maximum, not as a target to fill.
- Preserve Unicode notation when code or comments mirror a formal system.
- Keep packages and files aligned with conceptual ownership.

## 2. Brace-delimited syntax

Use braces for classes, traits, objects, enums, extensions, multiline methods,
and multiline control-flow bodies.

Preferred:

```scala
final class MessageQueue(capacity: Int) {
  private var messages: Vector[Message] = Vector.empty

  def enqueue(message: Message): Result[Unit, QueueError] = {
    if messages.size < capacity then {
      messages = messages :+ message
      Result.Ok(())
    } else {
      Result.Err(QueueError.CapacityReached(capacity))
    }
  }
}
```

Do not omit the braces around a multiline method body:

```scala
// Avoid
def enqueue(message: Message) =
  if messages.size < capacity then
    enqueueNow(message)
  else
    reject(message)
```

```scala
// Prefer
def enqueue(message: Message) = {
  if messages.size < capacity then
    enqueueNow(message)
  else
    reject(message)
}
```

```scala
// Or
def enqueue(message: Message) = {
  if messages.size < capacity then {
    enqueueNow(message)
  } else {
    reject(message)
  }
}
```

A short expression may remain on one line:

```scala
def isEmpty: Boolean = messages.isEmpty

val suffix = if count == 1 then "item" else "items"
```

An `if` with one short expression in each branch may use indentation inside the
surrounding braced method body:

```scala
def enqueue(message: Message): Result[Unit, QueueError] = {
  if messages.size < capacity then
    enqueueNow(message)
  else
    reject(message)
}
```

If either the `if` or `else` branch occupies multiple lines, put braces around
both branches. Never brace only one side:

```scala
if connection.isOpen then {
  connection.send(request)
  metrics.recordRequest()
} else {
  metrics.recordFailure()
  reconnect()
}
```

Avoid asymmetric branch delimiters:

```scala
if connection.isOpen then {
  connection.send(request)
  metrics.recordRequest()
} else
  reconnect()
```

The same symmetry applies to an `else if` chain: if one branch needs braces,
brace every branch in the chain.

Multiline bodies of other control-flow constructs also use braces:

```scala
while (pendingJobs.nonEmpty) {
  val job = pendingJobs.removeHead()
  execute(job)
}
```

## 3. Indentation, spacing, and blank lines

- Indent with two spaces. Do not use tabs.
- Put one space before an opening block brace.
- Put spaces around binary operators and after commas.
- Do not add spaces immediately inside parentheses or brackets.
- Separate logically distinct groups of statements with one blank line.
- Do not insert blank lines between every statement.
- Remove trailing whitespace.

Preferred:

```scala
val total = subtotal + tax
val coordinates = Vector(10, 20, 30)

if total > limit then {
  Result.Err(PaymentError.LimitExceeded(total, limit))
} else {
  Result.Ok(total)
}
```

### Keep same-level expressions at the same indentation

Sibling expressions at the same syntactic level start in the same column.
Do not add an extra indentation level merely because an expression continues
onto another line.

Preferred:

```scala
val canPublish =
  document.isValid &&
  author.isActive &&
  permissions.canPublish
```

Avoid:

```scala
val canPublish =
  document.isValid &&
    author.isActive &&
    permissions.canPublish
```

The rule applies to every long infix expression, not only boolean conditions:

```scala
val totalPrice =
  basePrice +
  shippingCost +
  tax

val polynomial =
  constant +
  linearCoefficient * value +
  quadraticCoefficient * value * value
```

Avoid indenting later operands as though they were children of the first
operand:

```scala
val totalPrice =
  basePrice +
    shippingCost +
    tax
```

Only a genuinely nested expression receives another indentation level:

```scala
val totalPrice =
  basePrice +
  calculateSurcharge(
    destination,
    packageWeight
  ) +
  tax
```

Here `basePrice`, `calculateSurcharge(...)`, and `tax` are peers. The arguments
of `calculateSurcharge` are nested inside that operand, so they are indented by
one additional level.

## 4. Line length and choosing a layout

Keep lines at or below 120 columns. Prefer the shortest layout that remains
easy to read. A short expression should not be split mechanically.

Preferred for a short constructor call:

```scala
Result.Err(ParseError.ExpectedToken(expectedToken, actualToken))
```

Also appropriate when the arguments deserve emphasis or the call becomes
long:

```scala
Result.Err(ParseError.ExpectedToken(
  expectedToken,
  actualToken,
  sourcePosition
))
```

Avoid unnecessary fragmentation:

```scala
Result.Err(
  ParseError.ExpectedToken(
    expectedToken,
    actualToken
  )
)
```

Do not force a complicated expression onto one line merely because it fits
under 120 columns. Layout should expose the expression's structure.

### Wrap argument lists as units

When a line break separates sibling arguments, put the first argument on the
line after `(` too. Indent the argument block by two spaces and align the closing
`)` with the indentation of the line that opened the list. Do not leave the first
arguments on the opening line and move only the remaining arguments below it,
even with correct indentation:

```scala
// Avoid
solver.add(native.mkImplies(active, native.mkAnd(inScope(head, size),
  inScope(length, maximum))))
```

Apply the rule to the argument list being split. Outer calls may remain
compact around a multiline nested argument, and closing parentheses may be
grouped. Both layouts below are valid:

```scala
solver.add(native.mkImplies(active, native.mkAnd(
  inScope(head, size),
  inScope(length, maximum)
)))

solver.add(
  native.mkImplies(active, native.mkAnd(
    inScope(head, size),
    inScope(length, maximum)
  ))
)
```

Short sibling arguments may share a line within the argument block. Keep the
whole expression inline when that is readable; wrapping a nested call does
not require expanding every enclosing call.

### Strict block indentation: never deep-align

Deep alignment has a specific meaning: a wrapped continuation is indented to
an interior text column established by the preceding line. For example, later
arguments may be placed directly beneath the first argument merely because the
first argument follows a long function name. That indentation is coupled to
the width of unrelated text and changes when an identifier is renamed.

Continuation lines instead use standard two-space block indentation.

Avoid:

```scala
someFunctionCall(firstArgument, secondArgument,
                 thirdArgument, fourthArgument)
```

If the complete call is short, keep it inline:

```scala
someFunctionCall(firstArgument, secondArgument, thirdArgument, fourthArgument)
```

If the call needs multiple lines, start the argument block on the next line
and indent it by two spaces:

```scala
someFunctionCall(
  firstArgument, secondArgument,
  thirdArgument, fourthArgument
)
```

Apply the same rule to declarations:

```scala
// Avoid
def createReport(title: String, generatedAt: Instant,
                 sections: Vector[Section], author: Author): Report = {
  ???
}
```

```scala
// Prefer
def createReport(
  title: String,
  generatedAt: Instant,
  sections: Vector[Section],
  author: Author
): Report = {
  ???
}
```

Do not align nested arguments beneath the nested function name:

```scala
// Avoid
Result.Ok(createReport(title,
                       generatedAt,
                       sections,
                       author))
```

```scala
// Prefer
Result.Ok(createReport(
  title,
  generatedAt,
  sections,
  author
))
```

Do not push a multiline branch expression to the column after `=>`:

```scala
// Avoid
case Event.Received(sender, payload) => dispatchMessage(
                                            sender,
                                            payload,
                                            deliveryContext
                                          )
```

```scala
// Prefer
case Event.Received(sender, payload) => {
  dispatchMessage(
    sender,
    payload,
    deliveryContext
  )
}
```

The indentation is determined by syntactic nesting, never by the visual width
of an identifier. Renaming `createReport` to `buildReport` must not require
reindenting its argument block.

The following are not deep alignment:

- keeping a short call entirely on one line;
- indenting a multiline argument block by two spaces;
- aligning a closing delimiter with the start of its construct;
- writing a multiline tuple pattern as `case (...) => { ... }`;
- indenting the body of `.foreach { case pattern => ... }` as an ordinary
  block;
- shallow column alignment among a few tiny, one-line cases.

These forms follow stable block structure rather than an interior text column:

```scala
someFunctionCall(
  firstArgument,
  secondArgument
)

entries.foreach { case (key, value) =>
  output.write(render(key, value))
}

(firstState, secondState) match {
  case (
    FirstState.Ready(first),
    SecondState.Ready(second)
  ) => {
    combine(first, second)
  }
}
```

For multiline argument and parameter lists:

- place the opening parenthesis on the declaration or call line;
- indent continued elements by two spaces;
- place the closing parenthesis at the indentation of the declaration or call;
- keep `extends`, the return type, or the opening brace on the closing line when
  that remains readable.

```scala
final case class DeliveryRequest(
  destination: Address,
  packages: Vector[Package],
  requestedWindow: DeliveryWindow
) extends Request {
  def packageCount: Int = packages.size
}
```

```scala
val request = DeliveryRequest(
  destination = customer.address,
  packages = packedItems,
  requestedWindow = preferredWindow
)
```

Named arguments are useful when several arguments share a type or when a
boolean would otherwise be unclear:

```scala
val account = Account(
  name = "Ada",
  enabled = true,
  administrator = false
)
```

Avoid unexplained positional booleans:

```scala
val account = Account("Ada", true, false)
```

## 5. Declarations and methods

Use a one-line method body when it is genuinely short:

```scala
def size: Int = entries.size

def contains(key: Key): Boolean = entries.contains(key)
```

Use `= { ... }` for multiline methods:

```scala
def insert(key: Key, value: Value): SymbolTable = {
  val updatedEntries = entries.updated(key, value)
  copy(entries = updatedEntries)
}
```

Public methods should normally declare their result type. Local private
helpers may rely on inference when the type is immediately obvious.

```scala
def decode(bytes: Array[Byte]): Result[Packet, DecodeError] = {
  decodeHeader(bytes).flatMap { header =>
    decodePayload(bytes, header)
  }
}
```

Prefer expression-oriented methods. Avoid `return` unless an early exit makes
an imperative boundary materially clearer.

Preferred:

```scala
def classify(score: Int): Grade = {
  if score >= 90 then Grade.Excellent
  else if score >= 60 then Grade.Passing
  else Grade.Failing
}
```

Avoid:

```scala
def classify(score: Int): Grade = {
  if score >= 90 then return Grade.Excellent
  if score >= 60 then return Grade.Passing
  return Grade.Failing
}
```

Keep companion objects next to their primary type when they jointly define
one abstraction:

```scala
final case class Port private (value: Int)

object Port {
  def from(value: Int): Result[Port, PortError] = {
    if value >= 0 && value <= 65535 then
      Result.Ok(Port(value))
    else
      Result.Err(PortError.OutOfRange(value))
  }
}
```

## 6. Pattern matching

Use a single line for a short pattern and a short result:

```scala
status match {
  case Status.Ready => start()
  case Status.Stopped => Result.Ok(())
}
```

For a branch inside an explicit `value match { ... }`, prefer braces after
`=>` when the branch body spans multiple lines. Braces are required when the
branch contains multiple statements and are still suggested when the branch
is one multiline expression:

```scala
status match {
  case Status.Ready => {
    val process = createProcess()
    process.start()
    Result.Ok(process)
  }
  case Status.Stopped => Result.Err(StartError.AlreadyStopped)
}
```

A short, single-line pattern may lead directly to one multiline expression
that already owns a block. Omitting the outer branch braces is acceptable when
that compact form is clearer, but it is not the default:

```scala
result match {
  case Result.Ok(value) => renderer.render(value) { document =>
    output.write(document)
  }
  case Result.Err(error) => report(error)
}
```

When a `case` pattern spans multiple lines, retain explicit parentheses around
the complete tuple pattern and put the branch body in braces. Align `) => {`
with `case (`:

```scala
(workerState, queueState) match {
  case (
    WorkerState.Available(worker),
    QueueState.Pending(job)
  ) => {
    Result.Ok(Assignment(worker, job))
  }

  case (
    WorkerState.Busy(_),
    QueueState.Pending(job)
  ) => {
    Result.Err(AssignmentError.NoWorkerAvailable(job.id))
  }

  case _ => Result.Err(AssignmentError.NothingToAssign)
}
```

Do not omit the tuple parentheses or leave a multiline branch body unbraced:

```scala
case
  WorkerState.Available(worker),
  QueueState.Pending(job) =>
  Result.Ok(Assignment(worker, job))
```

A long extractor pattern breaks within the extractor's parentheses and still
uses a braced branch body:

```scala
event match {
  case Shipment.Dispatched(
    shipmentId,
    destination,
    estimatedArrival
  ) => {
    tracker.record(shipmentId, destination)
    scheduler.expect(shipmentId, estimatedArrival)
  }

  case Shipment.Cancelled(shipmentId) => tracker.remove(shipmentId)
}
```

Use guards only for conditions that genuinely refine the pattern:

```scala
command match {
  case Command.Retry(attempts) if attempts < retryLimit => retry(attempts + 1)
  case Command.Retry(_) => Result.Err(CommandError.RetryLimitReached)
  case Command.Stop => Result.Ok(State.Stopped)
}
```

### Case lambdas in collection operations

For a `case` lambda passed to an operation such as `map`, `collect`, `forall`,
or `foreach`, keep a short pattern and result on one line:

```scala
val activeNames = users.collect {
  case User(name, true) => name
}
```

When `case` starts on the same line as the operation, the operation's braces
already delimit the body. Do not add another pair of braces after `=>` merely
because that body is multiline:

```scala
val summaries = records.map { case (identifier, record) =>
  val itemCount = record.items.size
  identifier -> Summary(itemCount, record.updatedAt)
}
```

When `case` starts on a following line, use ordinary branch formatting. Put a
multiline body in braces when it starts on a line after `=>`:

```scala
val allAssignmentsAreValid = assignments.forall {
  case (
    worker,
    job
  ) => {
    worker.skills.contains(job.requiredSkill) &&
    worker.region == job.region
  }
}
```

A short pattern may lead directly to a single expression. If that expression
already owns a block, keep its braces without adding an outer branch block,
even when `case` is on a separate line from the operation:

```scala
val entries = records.flatMap {
  case (identifier, record) => record.items.map { item =>
    identifier -> item
  }
}
```

Use ordinary two-space block indentation.

Small inline alignment is acceptable for a short, uniform group of simple
patterns:

```scala
state match {
  case State.Open   => "open"
  case State.Closed => "closed"
}
```

Ordinary unaligned branches are equally clear and should be used whenever the
patterns or results become more complex:

```scala
result match {
  case Result.Ok(value) => render(value)
  case Result.Err(error) => renderError(error)
}
```

Never extend shallow alignment into multiline expressions or use it to align
continuation lines beneath a constructor name.

## 7. Lambdas and collection transformations

Keep a short lambda inline:

```scala
val lengths = names.map(name => name.length)
```

Use braces for a multiline lambda:

```scala
val invoices = orders.map { order =>
  val subtotal = order.lines.map(_.price).sum
  val tax = taxPolicy.calculate(subtotal)
  Invoice(order.id, subtotal, tax)
}
```

Name accumulators and intermediate values clearly:

```scala
val index = documents.foldLeft(Map.empty[DocumentId, Document]) {
  (accumulated, document) => accumulated.updated(document.id, document)
}
```

Avoid obscure names when the scope is not tiny:

```scala
// Avoid
val index = documents.foldLeft(Map.empty[DocumentId, Document]) {
  (acc, doc) => acc.updated(doc.id, doc)
}
```

Keep short method chains on one line:

```scala
val names = users.filter(_.active).map(_.name)
```

Break a longer chain one operation per line:

```scala
val summaries = users.iterator
  .filter(_.active)
  .map(user => summarize(user, clock.now()))
  .sortBy(_.displayName)
  .toList
```

Do not hide important failure handling inside a dense chain. Introduce a named
intermediate value or a helper method when it clarifies the transformation.

## 8. Naming

Use:

- `PascalCase` for classes, traits, enums, enum cases, objects, and type aliases;
- `lowerCamelCase` for methods, values, variables, parameters, and fields;
- plural names for collections;
- `is`, `has`, `can`, or `should` prefixes for boolean queries;
- names that describe semantic roles rather than incidental representations.

```scala
final case class CustomerId(value: String)

final case class Customer(
  id: CustomerId,
  displayName: String,
  isActive: Boolean
)
```

Prefer complete names:

```scala
def evaluateExpression(expression: Expression, environment: Environment): Value

def recursiveDefinitions(module: Module): Set[Definition]

val accumulatedDiagnostics: Vector[Diagnostic]
```

Avoid unusual abbreviations:

```scala
def evalExpr(expr: Expr, env: Env): Val

val recurDefs: Set[Defn]

val accDiags: Vector[Diag]
```

Conventional language or domain abbreviations are acceptable when they are
clear to the expected reader. Examples include `eval`, `sub`, `func`,
`struct`, `lhs`, `rhs`, `id`, and conventional generic parameters such as
`T` and `E`. Even conventional abbreviations should not make a public API
ambiguous; prefer `customerId` over a bare `id` when several identities exist.

Do not encode type information redundantly in names:

```scala
// Avoid
val userString: String
val itemList: List[Item]

// Prefer
val username: String
val items: List[Item]
```

## 9. Object-oriented ownership and functional transformations

Put behavior on a type when one value clearly owns the invariant or operation.

```scala
final case class SourceSpan(start: Int, end: Int) {
  require(start <= end)

  def length: Int = end - start

  def contains(position: Int): Boolean = {
    position >= start && position < end
  }
}
```

The following reads naturally because the span owns its bounds:

```scala
if span.contains(position) then highlight(span)
```

Structural queries and intrinsic transformations should also live with their
representation:

```scala
sealed trait Schema {
  def isWellFormed: Boolean
  def normalized: Schema
  def split: Option[(Schema, Schema)]
}
```

Do not flatten such behavior into unrelated helpers:

```scala
// Avoid
def isSchemaWellFormed(schema: Schema): Boolean
def normalizeSchema(schema: Schema): Schema
def splitSchema(schema: Schema): Option[(Schema, Schema)]
```

Conversely, do not force a symmetric relation onto an arbitrary receiver. If
two peer values participate with equal priority, keep the operation neutral:

```scala
object ShapeRelations {
  def findIntersection(
    firstShape: Shape,
    secondShape: Shape
  ): Result[Shape, IntersectionError] = {
    ???
  }
}
```

Avoid implying ownership that does not exist:

```scala
// Avoid when both shapes have equal priority
firstShape.findIntersection(secondShape)
```

Behavior connecting distinct representations belongs at their boundary:

```scala
final class MessageDecoder(protocol: Protocol) {
  def decode(bytes: Array[Byte]): Result[Message, DecodeError] = {
    ???
  }
}
```

Do not put decoding policy into `Array[Byte]` or into the domain `Message`
merely to make the call look object oriented.

Small pure helpers remain appropriate when there is no meaningful owner:

```scala
private def combineChecks(
  first: Result[Unit, ValidationError],
  second: Result[Unit, ValidationError]
): Result[Unit, ValidationError] = {
  first.flatMap(_ => second)
}
```

## 10. Domain representations

Use an enum for a genuinely closed set of alternatives:

```scala
enum ConnectionState {
  case Disconnected
  case Connecting(attempt: Int)
  case Connected(session: Session)
}
```

Use a dedicated open value for extensible identifiers:

```scala
final case class PluginId(value: String)
```

Do not turn user-defined or externally defined names into an enum:

```scala
// Avoid
enum PluginId {
  case Formatter
  case Compiler
}
```

Use semantic wrappers when raw primitives would permit accidental mixing:

```scala
final case class UserId(value: Long)
final case class InvoiceId(value: Long)

def loadInvoice(userId: UserId, invoiceId: InvoiceId): Result[Invoice, LoadError]
```

Prefer immutable updates:

```scala
final case class Cart(items: Vector[Item]) {
  def add(item: Item): Cart = copy(items = items :+ item)
}
```

Mutation is acceptable inside a clearly stateful component or a locally
contained imperative algorithm. Keep the mutable state private and do not let
it weaken the public contract.

```scala
final class Counter {
  private var currentValue: Long = 0L

  def increment(): Long = {
    currentValue += 1
    currentValue
  }
}
```

## 11. Recoverable failures and `Result[T, E]`

Use an explicit result type for recoverable failures in pure functional logic.
The examples in this guide use this definition of `Result[T, E]`:

```scala
enum Result[+T, +E] {
  case Ok(value: T)
  case Err(error: E)

  def map[U](transform: T => U): Result[U, E] = this match {
    case Result.Ok(value) => Result.Ok(transform(value))
    case Result.Err(error) => Result.Err(error)
  }

  def flatMap[U, F](transform: T => Result[U, F]): Result[U, E | F] = this match {
    case Result.Ok(value) => transform(value)
    case Result.Err(error) => Result.Err(error)
  }

  def mapError[F](transform: E => F): Result[T, F] = this match {
    case Result.Ok(value) => Result.Ok(value)
    case Result.Err(error) => Result.Err(transform(error))
  }
}
```

Keep failure alternatives structured rather than using `Either` as a generic
disjunction:

```scala
enum RegistrationError {
  case EmptyName
  case DuplicateEmail(email: EmailAddress)
}

def register(
  name: String,
  email: EmailAddress,
  repository: UserRepository
): Result[User, RegistrationError] = {
  if name.isBlank then
    Result.Err(RegistrationError.EmptyName)
  else if repository.contains(email) then
    Result.Err(RegistrationError.DuplicateEmail(email))
  else
    Result.Ok(User(name, email))
}
```

Compose `Result` values with `map`, `flatMap`, or a `for` expression. Prefer
`for` when it flattens deeply nested callbacks and makes dependencies easier to
follow:

```scala
// Prefer
for {
  value <- load(key)
  checked <- validate(value)
  result <- transform(checked)
} yield value -> result

// Avoid deep nesting
load(key).flatMap { value =>
  validate(value).flatMap { checked =>
    transform(checked).map(result => value -> result)
  }
}
```

Straightforward chains are equally appropriate. Choose the clearer form rather
than converting every `map` or `flatMap` to `for`:

```scala
load(key).flatMap(validate).map(normalize)
```

Preserve structured errors while crossing layers:

```scala
def loadProfile(userId: UserId): Result[Profile, ProfileError] = {
  repository.load(userId).mapError {
    case RepositoryError.NotFound => ProfileError.UnknownUser(userId)
    case RepositoryError.Unavailable(cause) => ProfileError.StorageUnavailable(cause)
  }
}
```

Use `Option[T]` when absence is expected and requires no error information:

```scala
def preferredName(user: User): Option[String] = user.nickname
```

Do not use `Option` when callers need to distinguish failure causes.

Exceptions remain appropriate for:

- imperative boundaries where the surrounding API is exception-based;
- failed internal invariants;
- truly unrecoverable process conditions.

```scala
def requiredNode(identifier: NodeId): Node = {
  nodes.getOrElse(
    identifier,
    throw new IllegalStateException(s"Validated node is missing: $identifier")
  )
}
```

Do not throw exceptions for ordinary domain failures from an otherwise pure
API:

```scala
// Avoid
def parsePort(text: String): Port = {
  if text.forall(_.isDigit) then
    Port(text.toInt)
  else
    throw new IllegalArgumentException("Invalid port")
}
```

Do not compute semantics from formatted error strings. Match on the structured
error value.

## 12. Context parameters, givens, and extensions

Use Scala 3 `using` and `given` for genuine contextual dependencies. Give a
context parameter a name when its role matters or it is referenced directly.

```scala
def resolve(
  name: Name
)(using context: ResolutionContext): Result[Binding, ResolutionError] = {
  context.lookup(name) match {
    case Some(binding) => Result.Ok(binding)
    case None => Result.Err(ResolutionError.UnknownName(name))
  }
}
```

Keep contextual dependencies explicit in the architecture. Do not use a
`given` as hidden mutable global state.

Use extension methods when a type cannot be changed and the operation still
clearly belongs to that value:

```scala
extension (path: Path) {
  def scalaSource: Boolean = path.extension.contains("scala")
}
```

Do not collect unrelated helpers into a broad extension block merely to avoid
ordinary functions or cohesive service types.

## 13. Imports and source organization

Use one package declaration at the top of a file, followed by imports and then
definitions:

```scala
package example.catalog

import example.data.Repository
import example.model.{Item, ItemId}

import java.time.Instant
```

Group imports by origin when that makes dependencies easier to scan. Remove
unused imports. Prefer explicit or grouped imports; use wildcard imports only
when importing a deliberately cohesive family of names.

Organize files by conceptual ownership:

```text
catalog/
  Item.scala
  Catalog.scala
  CatalogError.scala
storage/
  CatalogRepository.scala
util/
  Result.scala
```

Do not place a cross-cutting abstraction inside whichever feature first needed
it. Conversely, do not turn `util` into a dumping ground for behavior that has
a clear domain owner.

Use the narrowest useful visibility:

```scala
private final class TokenCursor(tokens: Vector[Token]) {
  private var position: Int = 0
}
```

```scala
private[parser] object Recovery {
  def synchronize(cursor: TokenCursor): Unit = cursor.skipToStatementBoundary()
}
```

Keep cohesive small definitions together. Do not create one file per trivial
type mechanically, and do not allow unrelated concepts to accumulate in one
large file.

## 14. Comments and documentation

Comments should explain invariants, intent, non-obvious constraints, and the
reason for a design choice. Do not narrate straightforward syntax.

Useful:

```scala
// Preserve insertion order because diagnostics are reported in source order.
val diagnostics = Vector.newBuilder[Diagnostic]
```

Unhelpful:

```scala
// Increment the index.
index += 1
```

Use ScalaDoc for a public API whose contract is not self-evident:

```scala
/**
 * Returns the dependencies in compilation order.
 *
 * A dependency always precedes every node that imports it. Cycles are
 * reported as structured errors rather than omitted from the result.
 */
def compilationOrder: Result[Vector[ModuleId], DependencyError] = {
  ???
}
```

When code implements a formal rule, include the complete rule: premises, rule
name, and conclusion. Preserve the formalism's Unicode notation.

```scala
/*
 * Γ ⊢ condition ⇓ true    Γ ⊢ whenTrue ⇓ value
 * ───────────────────────────────────────────── Eval-Conditional-True
 * Γ ⊢ if condition then whenTrue else whenFalse ⇓ value
 */
case Expression.Conditional(condition, whenTrue, _) => {
  evaluate(condition, environment).flatMap {
    case Value.Boolean(true) => evaluate(whenTrue, environment)
    case other => Result.Err(EvaluationError.ExpectedBoolean(other))
  }
}
```

Do not replace the complete rule with only its identifier:

```scala
// Avoid: Eval-Conditional-True
```

Do not transliterate readable formal symbols into ASCII in formal comments:

```scala
// Prefer: Γ ⊢ expression ⇓ value
// Avoid:  Gamma |- expression evals-to value
```

## 15. Tests

Tests should describe behavior at a stable boundary. Use complete scenario
names and keep setup specific to the behavior under test.

```scala
test("expired sessions are rejected before authorization") {
  val clock = FixedClock.at(expiration.plusSeconds(1))
  val session = Session(expiresAt = expiration)

  val result = authorizer.authorize(session, Action.Read)

  assertEquals(result, Result.Err(AuthorizationError.ExpiredSession))
}
```

Use a table or helper when several cases exercise the same contract:

```scala
List(
  "" -> Result.Err(NameError.Empty),
  "Ada" -> Result.Ok(Name("Ada")),
  "  Ada  " -> Result.Ok(Name("Ada"))
).foreach { case (input, expected) =>
  test(s"name normalization handles '$input'") {
    assertEquals(Name.from(input), expected)
  }
}
```

Do not duplicate a private production algorithm in a test. Assert the public
result or a deliberately stable component contract instead.

When fixing a defect, test the underlying behavior class, including nearby
boundary cases, rather than adding a branch that recognizes one historical
input.
