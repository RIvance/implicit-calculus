import ImplicitSynthesis.SMT

/-! Finite validity refinement and projected ambiguity queries. -/

namespace ImplicitSynthesis.SMT
namespace Refinement

lemma Remove.member {j : ι} {R S} (h : Remove j R S) : j ∈ R := by {
  induction h with
  | here => exact List.Mem.head ..
  | there _ ih => exact List.Mem.tail _ ih
}

lemma Remove.partition {j R S} (h : Remove j R S) (k : ι) : k ∈ R ↔ k = j ∨ k ∈ S := by {
  induction h with
  | here =>
    {
      constructor
      · intro hk
        cases hk with
        | head => exact .inl rfl
        | tail _ hk => exact .inr hk
      · intro hk
        cases hk with
        | inl hk => cases hk; exact List.Mem.head ..
        | inr hk => exact List.Mem.tail _ hk
    }
  | there _ ih =>
    {
      constructor
      · intro hk
        cases hk with
        | head => exact .inr (List.Mem.head ..)
        | tail _ hk => cases ih.mp hk with
          | inl hk => exact .inl hk
          | inr hk => exact .inr (List.Mem.tail _ hk)
      · intro hk
        cases hk with
        | inl hk => exact List.Mem.tail _ (ih.mpr (.inl hk))
        | inr hk => cases hk with
          | head => exact List.Mem.head ..
          | tail _ hk => exact List.Mem.tail _ (ih.mpr (.inr hk))
    }
}

lemma Remove.length {j : ι} {R S} (h : Remove j R S) : Nat.succ S.length = R.length := by {
  induction h with
  | here => rfl
  | there _ ih => exact congrArg Nat.succ ih
}

lemma Step.preservesModels {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R K' R'} (h : Step B V K R K' R') (μ : M) :
  Complete B V K R μ ↔ Complete B V K' R' μ
:= by {
  cases h with
  | reject _ _ hr =>
    {
      constructor
      · rintro ⟨hB, hV⟩
        refine ⟨hB, ?_⟩
        intro k hk
        cases hk with
        | inl hk => cases hk with
          | head => exact hV _ (.inr hr.member)
          | tail _ hk => exact hV _ (.inl hk)
        | inr hk => exact hV _ (.inr ((hr.partition k).mpr (.inr hk)))
      · rintro ⟨hB, hV⟩
        refine ⟨hB, ?_⟩
        intro k hk
        cases hk with
        | inl hk => exact hV _ (.inl (List.Mem.tail _ hk))
        | inr hk => cases (hr.partition k).mp hk with
          | inl hk => cases hk; exact hV _ (.inl (List.Mem.head ..))
          | inr hk => exact hV _ (.inr hk)
    }
}

theorem Step.rejectsModel {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R K' R'} (h : Step B V K R K' R') :
  ∃ μ, Partial B V K μ ∧ ¬ Partial B V K' μ
:= by {
  cases h with
  | reject hs hv _ =>
    {
      refine ⟨_, Oracle.satSound hs, ?_⟩
      intro h'
      exact hv (h'.2 _ (List.Mem.head ..))
    }
}

lemma Step.fresh {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R j S} (h : Step B V K R (j :: K) S) : j ∉ K
:= by {
  cases h with
  | reject hs hv _ => exact fun hj => hv ((Oracle.satSound hs).2 _ hj)
}

lemma Step.length {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R K' R'} (h : Step B V K R K' R') :
  Nat.succ R'.length = R.length
:= by {
  cases h with
  | reject _ _ hr => exact hr.length
}

lemma Chain.length {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R K' R' n} (h : Chain B V K R K' R' n) :
  n + R'.length = R.length
:= by {
  induction h with
  | nil => exact Nat.zero_add _
  | cons hs _ ih =>
    {
      rw [Nat.succ_add, ih]
      exact hs.length
    }
}

theorem Chain.bound {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R K' R' n} (h : Chain B V K R K' R' n) : n ≤ R.length
:= by {
  rw [← h.length]
  exact Nat.le_add_right n R'.length
}

theorem Chain.preservesModels {B : M → Prop} {V : ι → M → Prop}
  [Oracle (List ι) M (Partial B V)] {K R K' R' n} (h : Chain B V K R K' R' n) (μ : M) :
  Complete B V K R μ ↔ Complete B V K' R' μ
:= by {
  induction h with
  | nil => exact Iff.rfl
  | cons hs _ ih => exact (hs.preservesModels μ).trans ih
}

lemma completeTerminal {B : M → Prop} {V : ι → M → Prop} {K μ} :
  Complete B V K [] μ ↔ Partial B V K μ
:= by {
  constructor
  · intro h
    exact ⟨h.1, fun j hj => h.2 j (.inl hj)⟩
  · intro h
    refine ⟨h.1, ?_⟩
    intro j hj
    cases hj with
    | inl hj => exact h.2 j hj
    | inr hj => cases hj
}

end Refinement

lemma differentSat {Holds : F → M → Prop} {Projects : M → K → Prop}
  [Oracle (Selection F K) M (SelectedHolds Holds Projects)] {φ κ μ}
  (h : Oracle.Responds (SelectedHolds Holds Projects) (.different φ κ) (.sat μ)) :
  Holds φ μ ∧ ∃ κ', Projects μ κ' ∧ κ' ≠ κ
:= by {
  exact Oracle.satSound (Holds := SelectedHolds Holds Projects) h
}

lemma differentUnsat {Holds : F → M → Prop} {Projects : M → K → Prop} [DecidableEq K]
  [Oracle (Selection F K) M (SelectedHolds Holds Projects)] {φ κ}
  (h : Oracle.Responds (SelectedHolds Holds Projects) (.different φ κ) .unsat) :
  ∀ μ κ', Holds φ μ → Projects μ κ' → κ' = κ
:= by {
  intro μ κ' hμ hκ
  cases decEq κ' κ with
  | isTrue heq => exact heq
  | isFalse hne => exact False.elim (Oracle.unsatSound h μ ⟨hμ, κ', hκ, hne⟩)
}

end ImplicitSynthesis.SMT
