import { Accordion, Badge, Button, Group } from '@mantine/core';

export function ResearchGuide({ onOpenExamples }: { onOpenExamples: () => void }) {
  return (
    <div className="research-guide">
      <Badge variant="light" color="teal">
        From evidence search to an SMT model
      </Badge>
      <h2>
        A goal is a type.
        <br />A solution is a construction.
      </h2>
      <p>
        The compiler inserts a goal when a program needs implicit evidence. At each goal, the lexical instance context Δ
        supplies the available ingredients.
      </p>
      <div className="guide-equation">Δ ⊢ p : A</div>
      <p>The admissible constructions are instance references, type applications, and implicit applications:</p>
      <div className="guide-equation">p ::= d ∣ p[B] ∣ p&#123;q&#125;</div>
      <Accordion defaultValue="spines" variant="separated" mt="lg">
        <Accordion.Item value="spines">
          <Accordion.Control>1. Read the evidence graph</Accordion.Control>
          <Accordion.Panel>
            A spine starts with an instance from Δ and applies a sequence of type or implicit arguments. Implicit
            arguments refer to earlier spines, so the graph is acyclic. Select a node to inspect its head, arguments,
            result type, and corresponding constraints.
          </Accordion.Panel>
        </Accordion.Item>
        <Accordion.Item value="constraints">
          <Accordion.Control>2. Inspect the actual constraints</Accordion.Control>
          <Accordion.Panel>
            Head indices select in-scope instances. Argument kinds follow the type being eliminated. Result equations
            connect each application to the next type, and the root must have the requested type.
            <p>
              Filter by a rule or a spine, switch between Unicode and SMT-LIB, or export the complete query for replay.
            </p>
          </Accordion.Panel>
        </Accordion.Item>
        <Accordion.Item value="models">
          <Accordion.Control>3. Follow model refinement</Accordion.Control>
          <Accordion.Panel>
            A satisfiable model chooses the heads, argument counts, type arguments, and earlier evidence references.
            Each used type argument must also be well scoped and within the height bound.
            <p>
              A provisional model that violates those premises adds guarded validity constraints. Search continues until
              a model passes or the search stops.
            </p>
          </Accordion.Panel>
        </Accordion.Item>
        <Accordion.Item value="bounds">
          <Accordion.Control>4. Experiment with the search bounds</Accordion.Control>
          <Accordion.Panel>
            <div className="guide-equation">cost = Σᵢ (1 + lengthᵢ) ≤ N</div>N bounds total construction size. H bounds
            the height of inferred type arguments. Increase either setting and rerun to explore a larger search space.
            <p>
              An exhausted bound does not establish that a type is uninhabited. A solver timeout or unknown result is
              also distinct from unsatisfiability.
            </p>
          </Accordion.Panel>
        </Accordion.Item>
        <Accordion.Item value="baseline">
          <Accordion.Control>5. Compare with exact lookup</Accordion.Control>
          <Accordion.Panel>
            Exact lookup selects the nearest instance whose type is already the goal. SMT search additionally
            instantiates polymorphic instances and supplies implicit prerequisites.
            <p>
              Use Compare search to run both strategies on the same source and bounds. Both paths use the compiler’s
              evidence validation and core type checker.
            </p>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>
      <Group mt="xl">
        <Button variant="light" onClick={onOpenExamples}>
          Explore programs
        </Button>
      </Group>
      <p className="muted small">
        Ctrl / ⌘ + Enter runs the program. Monaco’s command palette includes run, find, folding, and editor commands.
      </p>
    </div>
  );
}
