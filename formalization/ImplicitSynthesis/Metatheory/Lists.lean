import ImplicitSynthesis.Typing

/-! Constructive list lemmas used for contexts and unordered record fields. -/

namespace ImplicitSynthesis

lemma Lookup.unique {Γ : List α} {x A B} (h₁ : Lookup Γ x A) (h₂ : Lookup Γ x B) : A = B := by {
  induction h₁ with
  | here => cases h₂; rfl
  | @there Γ x A C h ih =>
    {
      generalize hy : Nat.succ x = y at h₂
      cases h₂ with
      | here => cases hy
      | there h₂ => cases Nat.succ.inj hy; exact ih h₂
    }
}

lemma Lookup.map (f : α → β) {Γ x A} (h : Lookup Γ x A) : Lookup (Γ.map f) x (f A) := by {
  induction h with
  | here => exact .here
  | there _ ih => exact .there ih
}

lemma Lookup.ofMap {f : α → β} {Γ x B} (h : Lookup (Γ.map f) x B) :
  ∃ A, Lookup Γ x A ∧ f A = B
:= by {
  induction Γ generalizing x with
  | nil => cases h
  | cons A Γ ih => cases h with
    | here => exact ⟨A, .here, rfl⟩
    | there h =>
      {
        rcases ih h with ⟨A, hA, hB⟩
        exact ⟨A, .there hA, hB⟩
      }
}

namespace Lists

lemma permMap (f : α → β) {R S : List α} (h : R.Perm S) : (R.map f).Perm (S.map f) := by {
  induction h with
  | nil => exact .nil
  | cons a _ ih => exact .cons (f a) ih
  | swap a b R => exact .swap (f a) (f b) (R.map f)
  | trans _ _ ih₁ ih₂ => exact .trans ih₁ ih₂
}

lemma permMem {R S : List α} (h : R.Perm S) {a : α} (ha : a ∈ R) : a ∈ S := by {
  induction h with
  | nil => exact ha
  | cons b _ ih => cases ha with
    | head => exact List.Mem.head ..
    | tail _ ha => exact List.Mem.tail _ (ih ha)
  | swap b c R =>
    {
      cases ha with
      | head => exact List.Mem.tail _ (List.Mem.head ..)
      | tail _ ha => cases ha with
        | head => exact List.Mem.head ..
        | tail _ ha => exact List.Mem.tail _ (List.Mem.tail _ ha)
    }
  | trans _ _ ih₁ ih₂ => exact ih₂ (ih₁ ha)
}

end Lists
end ImplicitSynthesis
