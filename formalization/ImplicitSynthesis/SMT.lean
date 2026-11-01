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

end ImplicitSynthesis.SMT
