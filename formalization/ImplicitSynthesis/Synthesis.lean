import ImplicitSynthesis.Resolution
import ImplicitSynthesis.SMT

/-!
Shared evidence programs and their mathematical SMT constraints. Programs and
argument lists are stored most recent first; references address earlier spines.
Type traces are model assignments, independently checked by local equations.
-/

namespace ImplicitSynthesis
namespace Synthesis

inductive Argument where
  | type (B : ImplicitType)
  | instance (k : Nat)

structure Spine where
  head : Nat
  arguments : List Argument

abbrev Program := List Spine

inductive HeightAtMost : Nat → ImplicitType → Prop where

  -- ───────────────── H-Int
  -- h(Int) ≤ H + 1
  | int : HeightAtMost (Nat.succ H) ℤ

  -- ───────────────── H-Var
  -- h(α) ≤ H + 1
  | var : HeightAtMost (Nat.succ H) #α

  -- h(A) ≤ H    h(B) ≤ H
  -- ───────────────────── H-Arrow
  -- h(A → B) ≤ H + 1
  | arrow : HeightAtMost H A → HeightAtMost H B → HeightAtMost (Nat.succ H) (A ⟶ B)

  -- h(A) ≤ H    h(B) ≤ H
  -- ───────────────────── H-Implicit-Arrow
  -- h(A ⇒ B) ≤ H + 1
  | implicitArrow :
      HeightAtMost H A → HeightAtMost H B → HeightAtMost (Nat.succ H) (A ⇒ B)

  -- h(A) ≤ H
  -- ───────────────── H-All
  -- h(∀α. A) ≤ H + 1
  | all : HeightAtMost H A → HeightAtMost (Nat.succ H) (∀. A)

  -- h(Aℓ) ≤ H for every field ℓ
  -- ─────────────────────────── H-Record
  -- h({ℓ:Aℓ}) ≤ H + 1
  | record : (∀ ℓ A, (ℓ, A) ∈ R → HeightAtMost H A) → HeightAtMost (Nat.succ H) {R}

def Valid (q H : Nat) (B : ImplicitType) : Prop := (q ⊢ⁱᵀ B) ∧ HeightAtMost H B

def cost : Program → Nat
  | [] => 0
  | s :: π => 1 + s.arguments.length + cost π

inductive ArgumentsValid (V : ImplicitType → Prop) : List Argument → Prop where

  -- ──────── V-Nil
  -- V([])
  | nil : ArgumentsValid V []

  -- V(B)    V(κ)
  -- ───────────── V-Type
  -- V([B] :: κ)
  | type : V B → ArgumentsValid V κ → ArgumentsValid V (.type B :: κ)

  -- V(κ)
  -- ───────────── V-Instance
  -- V(⟨k⟩ :: κ)
  | instance : ArgumentsValid V κ → ArgumentsValid V (.instance k :: κ)

inductive DecodeSteps (ps : List Evidence) : Evidence → List Argument → Evidence → Prop where

  -- ─────────────── D-Done
  -- p · [] ⇝ p
  | nil : DecodeSteps ps p [] p

  -- p · κ ⇝ r
  -- ───────────────────── D-Type
  -- p · ([B] :: κ) ⇝ r[B]
  | type : DecodeSteps ps p κ r → DecodeSteps ps p (.type B :: κ) (r⟦B⟧)

  -- p · κ ⇝ r    ps[k] = u
  -- ───────────────────────── D-Instance
  -- p · (⟨k⟩ :: κ) ⇝ r⟨u⟩
  | instance :
      DecodeSteps ps p κ r → Lookup ps k u → DecodeSteps ps p (.instance k :: κ) (r⟨u⟩)

inductive DecodeSpine (ps : List Evidence) : Spine → Evidence → Prop where

  -- dⱼ · κ ⇝ p
  -- ────────────────── D-Spine
  -- (j, κ) ⇝ p
  | intro : DecodeSteps ps $[j] κ p → DecodeSpine ps ⟨j, κ⟩ p

inductive DecodeProgram : Program → List Evidence → Prop where

  -- ───────── D-Empty
  -- [] ⇝ []
  | nil : DecodeProgram [] []

  -- π ⇝ ps    ps ⊢ s ⇝ p
  -- ────────────────────── D-Program
  -- s :: π ⇝ p :: ps
  | cons : DecodeProgram π ps → DecodeSpine ps s p → DecodeProgram (s :: π) (p :: ps)

/-- Local type equations; no evidence or typing derivation occurs here. -/
inductive StepConstraints (V : ImplicitType → Prop) (U : List ImplicitType) :
  ImplicitType → List Argument → List ImplicitType → ImplicitType → Prop where

  -- ────────────────── C-Done
  -- A · [] : [A] ⇒ A
  | nil : StepConstraints V U A [] [A] A

  -- A · κ : τ ⇒ ∀α.C    V(B)
  -- ─────────────────────────────────── C-Type
  -- A · ([B] :: κ) : C[B/α] :: τ ⇒ C[B/α]
  | type :
      StepConstraints V U A κ τ (∀. C) → V B →
      StepConstraints V U A (.type B :: κ) (C.instantiate B :: τ) (C.instantiate B)

  -- A · κ : τ ⇒ B ⇒ C    U[k] = B
  -- ───────────────────────────────── C-Instance
  -- A · (⟨k⟩ :: κ) : C :: τ ⇒ C
  | instance :
      StepConstraints V U A κ τ (B ⇒ C) → Lookup U k B →
      StepConstraints V U A (.instance k :: κ) (C :: τ) C

inductive SpineConstraints (V : ImplicitType → Prop) (Δ U : List ImplicitType) :
  Spine → List ImplicitType → ImplicitType → Prop where

  -- Δ[j] = A    A · κ : τ ⇒ B
  -- ────────────────────────── C-Spine
  -- (j, κ) : τ ⇒ B
  | intro : Lookup Δ j A → StepConstraints V U A κ τ B → SpineConstraints V Δ U ⟨j, κ⟩ τ B

inductive ProgramConstraints (V : ImplicitType → Prop) (Δ : List ImplicitType) :
  Program → List (List ImplicitType) → List ImplicitType → Prop where

  -- ───────────────── C-Empty
  -- [] : [] ⇒ []
  | nil : ProgramConstraints V Δ [] [] []

  -- π : Τ ⇒ U    Δ; U ⊢ s : τ ⇒ A
  -- ─────────────────────────────── C-Program
  -- s :: π : τ :: Τ ⇒ A :: U
  | cons :
      ProgramConstraints V Δ π Τ U → SpineConstraints V Δ U s τ A →
      ProgramConstraints V Δ (s :: π) (τ :: Τ) (A :: U)

/-- Typed shared programs, stated in terms of decoded evidence judgments. -/
inductive ProgramTyping (q : Nat) (V : ImplicitType → Prop) (Δ : List ImplicitType) :
  Program → List Evidence → List ImplicitType → Prop where

  -- ──────────────── P-Empty
  -- [] ⇝ [] : []
  | nil : ProgramTyping q V Δ [] [] []

  -- π ⇝ ps : U    ps ⊢ s ⇝ p    q; Δ ⊢ev p : A    V(s.arguments)
  -- ──────────────────────────────────────────────────────────── P-Program
  -- s :: π ⇝ p :: ps : A :: U
  | cons :
      ProgramTyping q V Δ π ps U → DecodeSpine ps s p → (q ∣ Δ ⊢ᵉ p ∶ A) →
      ArgumentsValid V s.arguments → ProgramTyping q V Δ (s :: π) (p :: ps) (A :: U)

structure Query where
  depth : Nat
  instances : List ImplicitType
  goal : ImplicitType
  costBound : Nat
  heightBound : Nat

structure Model where
  program : Program
  traces : List (List ImplicitType)
  types : List ImplicitType

def Holds (φ : Query) (μ : Model) : Prop :=
  ProgramConstraints (Valid φ.depth φ.heightBound) φ.instances μ.program μ.traces μ.types ∧
  Lookup μ.types 0 φ.goal ∧ cost μ.program ≤ φ.costBound

def Witness (φ : Query) (π : Program) : Prop :=
  ∃ ps U, ProgramTyping φ.depth (Valid φ.depth φ.heightBound) φ.instances π ps U ∧
    Lookup U 0 φ.goal ∧ cost π ≤ φ.costBound

def BoundedEvidence (φ : Query) (p : Evidence) : Prop :=
  ∃ π ps U, ProgramTyping φ.depth (Valid φ.depth φ.heightBound) φ.instances π ps U ∧
    Lookup ps 0 p ∧ Lookup U 0 φ.goal ∧ cost π ≤ φ.costBound

def Projects (μ : Model) (κ : EvidenceShape) : Prop :=
  ∃ ps p, DecodeProgram μ.program ps ∧ Lookup ps 0 p ∧ p.shape = κ

def OracleSearch (N H : Nat) [SMT.Oracle Query Model Holds] : Search := fun q Δ A p =>
  ∃ μ ps, SMT.Oracle.Responds Holds ⟨q, Δ, A, N, H⟩ (.sat μ) ∧
    DecodeProgram μ.program ps ∧ Lookup ps 0 p

end Synthesis
end ImplicitSynthesis
