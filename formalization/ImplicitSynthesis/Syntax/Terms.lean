import ImplicitSynthesis.Syntax.Types

/-! The implicit calculus, its admissible evidence, and the explicit target. -/

namespace ImplicitSynthesis

inductive Evidence where
  | ref (d : Nat)
  | typeApp (p : Evidence) (A : ImplicitType)
  | implicitApp (p q : Evidence)
  deriving Repr

inductive EvidenceShape where
  | ref (d : Nat)
  | typeApp (p : EvidenceShape)
  | implicitApp (p r : EvidenceShape)
  deriving DecidableEq

def Evidence.shape : Evidence → EvidenceShape
  | .ref d => .ref d
  | .typeApp p _ => .typeApp p.shape
  | .implicitApp p r => .implicitApp p.shape r.shape

inductive ImplicitTerm where
  | var (x : Nat)
  | instance (d : Nat)
  | int (n : Int)
  | lam (A : ImplicitType) (c : ImplicitTerm)
  | implicitLam (A : ImplicitType) (c : ImplicitTerm)
  | app (c₁ c₂ : ImplicitTerm)
  | implicitApp (c₁ c₂ : ImplicitTerm)
  | typeLam (c : ImplicitTerm)
  | typeApp (c : ImplicitTerm) (A : ImplicitType)
  | letIn (A : ImplicitType) (c₁ c₂ : ImplicitTerm)
  | instanceLet (A : ImplicitType) (c₁ c₂ : ImplicitTerm)
  | fix (A : ImplicitType) (c : ImplicitTerm)
  | need (A : ImplicitType)
  | record (R : List (Label × ImplicitTerm))
  | proj (c : ImplicitTerm) (ℓ : Label)
  deriving Repr

inductive CoreTerm where
  | var (x : Nat)
  | int (n : Int)
  | lam (A : CoreType) (t : CoreTerm)
  | app (t₁ t₂ : CoreTerm)
  | typeLam (t : CoreTerm)
  | typeApp (t : CoreTerm) (A : CoreType)
  | letIn (t₁ t₂ : CoreTerm)
  | fix (A : CoreType) (t : CoreTerm)
  | record (R : List (Label × CoreTerm))
  | proj (t : CoreTerm) (ℓ : Label)
  deriving Repr

end ImplicitSynthesis
