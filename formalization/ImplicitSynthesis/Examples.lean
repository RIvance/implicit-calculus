import ImplicitSynthesis.Metatheory.Synthesis

/-! Concrete derivations exercising binding, records, evidence search, and ambiguity. -/

namespace ImplicitSynthesis.Examples

/-- Declarative search admits precisely the evidence derivations. -/
def declarative : Search := fun q Δ A p => q ∣ Δ ⊢ᵉ p ∶ A

/-- Λα. λ(x:α). x : ∀α. α → α. -/
theorem polymorphicIdentity :
  0 ∣ [] ∣ [] ⊢ⁱ .typeLam (.lam (.var 0) (.var 0)) ∶ .all (.arrow (.var 0) (.var 0))
:= by {
  exact .typeLam (.lam (.var (Nat.zero_lt_succ 0)) (.var .here))
}

/-- λ⟨d:Int⟩. λ(x:Int). ?Int resolves to λ(d:Int). λ(x:Int). d. -/
theorem instanceUnderOrdinaryBinder :
  declarative ∣ 0 ∣ [] ∣ id ∣ id ⊢ .implicitLam .int (.lam .int (.need .int)) ⇝
    .lam .int (.lam .int (.var 1))
:= by {
  exact .implicitLam (.lam (.need (.ref .here) (.ref .here) .ref))
}

/-- λ(x:Int). λ⟨d:Int⟩. x resolves to λ(x:Int). λ(d:Int). x. -/
theorem ordinaryUnderInstanceBinder :
  declarative ∣ 0 ∣ [] ∣ id ∣ id ⊢ .lam .int (.implicitLam .int (.var 0)) ⇝
    .lam .int (.lam .int (.var 1))
:= by {
  exact .lam (.implicitLam .var)
}

/-- instance d:Int = 7 in let x:Int = 2 in ?Int selects d. -/
theorem localInstance :
  declarative ∣ 0 ∣ [] ∣ id ∣ id ⊢ .instanceLet .int (.int 7) (.letIn .int (.int 2) (.need .int)) ⇝
    .letIn (.int 7) (.letIn (.int 2) (.var 1))
:= by {
  exact .instanceLet .int (.letIn .int (.need (.ref .here) (.ref .here) .ref))
}

theorem localInstanceCoreTyping :
  0 ∣ [] ⊢ᶜ .letIn (.int 7) (.letIn (.int 2) (.var 1)) ∶ .int
:= by {
  exact Resolves.closedPreservesTyping (.instanceLet .int (.letIn .int (.need .int))) localInstance
}

/-- The calculus permits fix x:Int. x; no termination claim is made. -/
theorem unrestrictedFix : 0 ∣ [] ∣ [] ⊢ⁱ .fix .int (.var 0) ∶ .int := by {
  exact .fix .int (.var .here)
}

/-- Instantiating the outer α beneath ∀β preserves the free variable γ. -/
theorem captureAvoidance :
  (ImplicitType.all (.arrow (.var 1) (.var 0))).instantiate (.var 0) =
    .all (.arrow (.var 1) (.var 0))
:= by rfl

/-- {2 = 7, 0 = 3} has the unordered record type {0:Int, 2:Int}. -/
theorem reorderedRecord :
  0 ∣ [] ∣ [] ⊢ⁱ .record [(2, .int 7), (0, .int 3)] ∶ .record [(0, .int), (2, .int)]
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
  ⟨0, [.all (.implicitArrow (.var 0) (.var 0)), .int], .int, 4, 1⟩

def polymorphicModel : Model :=
  ⟨[⟨0, [.instance 0, .type .int]⟩, ⟨1, []⟩],
   [[.int, .implicitArrow .int .int, .all (.implicitArrow (.var 0) (.var 0))], [.int]],
   [.int, .int]⟩

theorem polymorphicModelSatisfies : Holds polymorphicQuery polymorphicModel := by {
  refine ⟨?_, .here, Nat.le.refl⟩
  exact .cons (.cons .nil (.intro (.there .here) .nil))
    (.intro .here (.instance (.type .nil ⟨.int, .int⟩) .here))
}

theorem polymorphicModelDecodes :
  DecodeProgram polymorphicModel.program [.implicitApp (.typeApp (.ref 0) .int) (.ref 1), .ref 1]
:= by {
  exact .cons (.cons .nil (.intro .nil)) (.intro (.instance (.type .nil) .here))
}

theorem synthesizedPolymorphicEvidence :
  0 ∣ polymorphicQuery.instances ⊢ᵉ .implicitApp (.typeApp (.ref 0) .int) (.ref 1) ∶ .int
:= by {
  rcases polymorphicModelSatisfies.typedEvidence with ⟨ps, p, hd, hp, ht⟩
  cases polymorphicModelDecodes.deterministic hd
  cases hp
  exact ht
}

/-- Two distinct instance bindings yield distinct bounded evidence shapes. -/
def ambiguousQuery : Query := ⟨0, [.int, .int], .int, 1, 1⟩

theorem twoInstanceChoices :
  BoundedEvidence ambiguousQuery (.ref 0) ∧ BoundedEvidence ambiguousQuery (.ref 1) ∧
    (Evidence.ref 0).shape ≠ (Evidence.ref 1).shape
:= by {
  refine ⟨?_, ?_, ?_⟩
  · exact ⟨[⟨0, []⟩], [.ref 0], [.int], .cons .nil (.intro .nil) (.ref .here) .nil,
      .here, .here, Nat.le.refl⟩
  · exact ⟨[⟨1, []⟩], [.ref 1], [.int], .cons .nil (.intro .nil) (.ref (.there .here)) .nil,
      .here, .here, Nat.le.refl⟩
  · intro h
    cases h
}

/-- An ordinary function instance can be selected, but cannot be automatically applied. -/
theorem ordinaryArrowOnly {q p A} (h : q ∣ [.arrow .int .int] ⊢ᵉ p ∶ A) : A = .arrow .int .int := by {
  generalize hΔ : ([.arrow .int .int] : List ImplicitType) = Δ at h
  induction h with
  | ref h => cases hΔ; cases h with
    | here => rfl
    | there h => cases h
  | typeApp _ _ ih => cases ih hΔ
  | implicitApp _ _ ih _ => cases ih hΔ
}

theorem noOrdinaryApplication {q p} : ¬ (q ∣ [.arrow .int .int] ⊢ᵉ p ∶ .int) := by {
  intro h
  cases ordinaryArrowOnly h
}

end ImplicitSynthesis.Examples
