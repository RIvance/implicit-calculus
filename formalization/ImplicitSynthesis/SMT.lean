import Std

/-!
An abstract SMT boundary. Soundness is supplied with an oracle, not asserted
as an axiom. Neither termination of search nor completeness is assumed.
-/

namespace ImplicitSynthesis.SMT

inductive Answer (M : Type) where
  | sat (μ : M)
  | unsat
  | unknown

/-- The response relation permits different valid answers on different calls. -/
class Oracle (F M : Type) (Holds : F → M → Prop) where
  Responds : F → Answer M → Prop
  satSound : ∀ {φ μ}, Responds φ (.sat μ) → Holds φ μ
  unsatSound : ∀ {φ}, Responds φ .unsat → ∀ μ, ¬ Holds φ μ

namespace Refinement

/-- Removing one outstanding validity obligation. -/
inductive Remove (j : ι) : List ι → List ι → Prop where

  -- ──────────────── R-Here
  -- j :: R ∖ j = R
  | here : Remove j (j :: R) R

  -- R ∖ j = S
  -- ───────────────────── R-There
  -- k :: R ∖ j = k :: S
  | there : Remove j R S → Remove j (k :: R) (k :: S)

def Partial (B : M → Prop) (V : ι → M → Prop) (K : List ι) (μ : M) : Prop :=
  B μ ∧ ∀ j, j ∈ K → V j μ

def Complete (B : M → Prop) (V : ι → M → Prop) (K R : List ι) (μ : M) : Prop :=
  B μ ∧ ∀ j, j ∈ K ∨ j ∈ R → V j μ

inductive Step (B : M → Prop) (V : ι → M → Prop) [Oracle (List ι) M (Partial B V)] :
  List ι → List ι → List ι → List ι → Prop where

  -- solve(Φ(K)) = sat μ    ¬Vⱼ(μ)    R ∖ j = S
  -- ─────────────────────────────────────────── Refine
  -- (K, R) ↝ (j :: K, S)
  | reject :
      Oracle.Responds (Partial B V) K (.sat μ) → ¬ V j μ → Remove j R S → Step B V K R (j :: K) S

inductive Chain (B : M → Prop) (V : ι → M → Prop) [Oracle (List ι) M (Partial B V)] :
  List ι → List ι → List ι → List ι → Nat → Prop where

  -- ─────────────── F-Zero
  -- (K, R) ↝⁰ (K, R)
  | nil : Chain B V K R K R 0

  -- (K, R) ↝ (K₁, R₁)    (K₁, R₁) ↝ⁿ (K₂, R₂)
  -- ──────────────────────────────────────────── F-Succ
  -- (K, R) ↝ⁿ⁺¹ (K₂, R₂)
  | cons : Step B V K R K₁ R₁ → Chain B V K₁ R₁ K₂ R₂ n → Chain B V K R K₂ R₂ (Nat.succ n)

end Refinement

/-- Queries that exclude an entire projected solution, rather than one raw model. -/
inductive Selection (F K : Type) where
  | any (φ : F)
  | different (φ : F) (κ : K)

def SelectedHolds (Holds : F → M → Prop) (Projects : M → K → Prop) : Selection F K → M → Prop
  | .any φ, μ => Holds φ μ
  | .different φ κ, μ => Holds φ μ ∧ ∃ κ', Projects μ κ' ∧ κ' ≠ κ

end ImplicitSynthesis.SMT
