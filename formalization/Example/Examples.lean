import ImplicitSynthesis.Metatheory.Synthesis

/-! Concrete derivations exercising binding, records, evidence search, and ambiguity. -/

namespace Example

open ImplicitSynthesis

/-- Declarative search admits precisely the evidence derivations. -/
def declarative : Search := fun q Δ A p => q ∣ Δ ⊢ᵉ p ∶ A

/-- Λα. λ(x:α). x : ∀α. α → α. -/
lemma polymorphicIdentity :
  0 ∣ [] ∣ [] ⊢ⁱ Λ. λ[#0]. $[0] ∶ ∀. #0 ⟶ #0
:= by {
  exact .typeLam (.lam (.var (Nat.zero_lt_succ 0)) (.var .here))
}

/-- λ⟨d:Int⟩. λ(x:Int). ?Int resolves to λ(d:Int). λ(x:Int). d. -/
lemma instanceUnderOrdinaryBinder :
  declarative ∣ 0 ∣ [] ∣ id ∣ id ⊢ λ⟨ℤ⟩. λ[ℤ]. ?[ℤ] ⇝ λ[ℤ]. λ[ℤ]. $[1]
:= by {
  exact .implicitLam (.lam (.need (.ref .here) (.ref .here) .ref))
}

/-- λ(x:Int). λ⟨d:Int⟩. x resolves to λ(x:Int). λ(d:Int). x. -/
lemma ordinaryUnderInstanceBinder :
  declarative ∣ 0 ∣ [] ∣ id ∣ id ⊢ λ[ℤ]. λ⟨ℤ⟩. $[0] ⇝ λ[ℤ]. λ[ℤ]. $[1]
:= by {
  exact .lam (.implicitLam .var)
}

/-- instance d:Int = 7 in let x:Int = 2 in ?Int selects d. -/
lemma localInstance :
  declarative ∣ 0 ∣ [] ∣ id ∣ id ⊢ instance[ℤ] ⌜7⌝ in let[ℤ] ⌜2⌝ in ?[ℤ] ⇝
    let ⌜7⌝ in let ⌜2⌝ in $[1]
:= by {
  exact .instanceLet .int (.letIn .int (.need (.ref .here) (.ref .here) .ref))
}

lemma localInstanceCoreTyping :
  0 ∣ [] ⊢ᶜ let ⌜7⌝ in let ⌜2⌝ in $[1] ∶ ℤ
:= by {
  exact Resolves.closedPreservesTyping (.instanceLet .int (.letIn .int (.need .int))) localInstance
}

/-- The calculus permits fix x:Int. x; no termination claim is made. -/
lemma unrestrictedFix : 0 ∣ [] ∣ [] ⊢ⁱ fix[ℤ]. $[0] ∶ ℤ := by {
  exact .fix .int (.var .here)
}

/-- Instantiating the outer α beneath ∀β preserves the free variable γ. -/
lemma captureAvoidance :
  (∀. #1 ⟶ #0 : ImplicitType).instantiate #0 = (∀. #1 ⟶ #0 : ImplicitType)
:= by rfl

/-- {2 = 7, 0 = 3} has the unordered record type {0:Int, 2:Int}. -/
lemma reorderedRecord :
  0 ∣ [] ∣ [] ⊢ⁱ {[(2, ⌜7⌝), (0, ⌜3⌝)]} ∶ {[(0, ℤ), (2, ℤ)]}
:= by {
  apply ImplicitTyping.record
  · apply ImplicitWellFormed.record
    · apply List.Pairwise.cons
      · intro a h
        cases h with
        | head => exact Nat.zero_lt_succ 1
        | tail _ h => cases h
      · exact .cons (fun _ h => by cases h) .nil
    · intro ℓ A h
      cases h with
      | head => exact .int
      | tail _ h => cases h with
        | head => exact .int
        | tail _ h => cases h
  · exact .swap 0 2 []
  · intro ℓ c A hc hA
    cases hc with
    | head => cases hA with
      | tail _ hA => cases hA with
        | head => exact .int
        | tail _ hA => cases hA
    | tail _ hc => cases hc with
      | head => cases hA with
        | head => exact .int
        | tail _ hA => cases hA with
          | tail _ hA => cases hA
      | tail _ hc => cases hc
}

open Synthesis

/-- f:∀α.α ⇒ α, d:Int ⊢ f[Int]⟨d⟩ : Int, with shared-program cost four. -/
def polymorphicQuery : Query :=
  ⟨0, [∀. #0 ⇒ #0, ℤ], ℤ, 4, 1⟩

def polymorphicModel : Model :=
  ⟨[⟨0, [.instance 0, .type ℤ]⟩, ⟨1, []⟩], [[ℤ, ℤ ⇒ ℤ, ∀. #0 ⇒ #0], [ℤ]], [ℤ, ℤ]⟩

lemma polymorphicModelSatisfies : Holds polymorphicQuery polymorphicModel := by {
  refine ⟨?_, .here, Nat.le.refl⟩
  exact .cons (.cons .nil (.intro (.there .here) .nil))
    (.intro .here (.instance (.type .nil ⟨.int, .int⟩) .here))
}

lemma polymorphicModelDecodes :
  DecodeProgram polymorphicModel.program [$[0]⟦ℤ⟧⟨$[1]⟩, $[1]]
:= by {
  exact .cons (.cons .nil (.intro .nil)) (.intro (.instance (.type .nil) .here))
}

lemma synthesizedPolymorphicEvidence :
  0 ∣ polymorphicQuery.instances ⊢ᵉ $[0]⟦ℤ⟧⟨$[1]⟩ ∶ ℤ
:= by {
  rcases polymorphicModelSatisfies.typedEvidence with ⟨ps, p, hd, hp, ht⟩
  cases polymorphicModelDecodes.deterministic hd
  cases hp
  exact ht
}

/-- Two distinct instance bindings yield distinct bounded evidence shapes. -/
def ambiguousQuery : Query := ⟨0, [ℤ, ℤ], ℤ, 1, 1⟩

lemma twoInstanceChoices :
  BoundedEvidence ambiguousQuery $[0] ∧ BoundedEvidence ambiguousQuery $[1] ∧
    ($[0] : Evidence).shape ≠ ($[1] : Evidence).shape
:= by {
  refine ⟨?_, ?_, ?_⟩
  · exact ⟨[⟨0, []⟩], [$[0]], [ℤ], .cons .nil (.intro .nil) (.ref .here) .nil,
      .here, .here, Nat.le.refl⟩
  · exact ⟨[⟨1, []⟩], [$[1]], [ℤ], .cons .nil (.intro .nil) (.ref (.there .here)) .nil,
      .here, .here, Nat.le.refl⟩
  · intro h
    cases h
}

/-- An ordinary function instance can be selected, but cannot be automatically applied. -/
lemma ordinaryArrowOnly {q p A} (h : q ∣ [ℤ ⟶ ℤ] ⊢ᵉ p ∶ A) : A = (ℤ ⟶ ℤ : ImplicitType) := by {
  generalize hΔ : ([ℤ ⟶ ℤ] : List ImplicitType) = Δ at h
  induction h with
  | ref h => cases hΔ; cases h with
    | here => rfl
    | there h => cases h
  | typeApp _ _ ih => cases ih hΔ
  | implicitApp _ _ ih _ => cases ih hΔ
}

lemma noOrdinaryApplication {q p} : ¬ (q ∣ [ℤ ⟶ ℤ] ⊢ᵉ p ∶ ℤ) := by {
  intro h
  cases ordinaryArrowOnly h
}

end Example
