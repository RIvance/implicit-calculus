import ImplicitSynthesis.Typing

/-! Erasure commutes with the binding operations used by resolution. -/

namespace ImplicitSynthesis

/-- A canonical row contains at most one type at any label. -/
theorem Canonical.uniqueField {R : List (Label × α)} (h : Canonical R)
  {ℓ A B} (hA : (ℓ, A) ∈ R) (hB : (ℓ, B) ∈ R) : A = B
:= by {
  induction h with
  | nil => cases hA
  | cons hlt _ ih => cases hA with
    | head => cases hB with
      | head => rfl
      | tail _ hB => exact False.elim (Nat.lt_irrefl ℓ (hlt _ hB))
    | tail _ hA => cases hB with
      | head => exact False.elim (Nat.lt_irrefl ℓ (hlt _ hA))
      | tail _ hB => exact ih hA hB
}

/-- Canonical rows with the same fields are equal as representations. -/
theorem Canonical.ext {R S : List (Label × α)} (hR : Canonical R) (hS : Canonical S)
  (hRS : ∀ a, a ∈ R ↔ a ∈ S) : R = S
:= by {
  induction R generalizing S with
  | nil => cases S with
    | nil => rfl
    | cons s S => cases (hRS s).mpr (List.Mem.head ..)
  | cons r R ih =>
    {
      cases S with
      | nil => cases (hRS r).mp (List.Mem.head ..)
      | cons s S =>
        {
          cases hR with
          | cons hltR hR => cases hS with
            | cons hltS hS =>
              {
                have hr := (hRS r).mp (List.Mem.head ..)
                cases hr with
                | head =>
                  {
                    apply congrArg (List.cons r)
                    apply ih hR hS
                    intro a
                    constructor
                    · intro ha
                      cases (hRS a).mp (List.Mem.tail _ ha) with
                      | head => exact False.elim (Nat.lt_irrefl r.1 (hltR _ ha))
                      | tail _ ha => exact ha
                    · intro ha
                      cases (hRS a).mpr (List.Mem.tail _ ha) with
                      | head => exact False.elim (Nat.lt_irrefl r.1 (hltS _ ha))
                      | tail _ ha => exact ha
                  }
                | tail _ hr =>
                  {
                    cases (hRS s).mpr (List.Mem.head ..) with
                    | head => exact False.elim (Nat.lt_irrefl r.1 (hltS _ hr))
                    | tail _ hs => exact False.elim (Nat.lt_irrefl r.1 (Nat.lt_trans (hltR _ hs) (hltS _ hr)))
                  }
              }
        }
    }
}

namespace ImplicitType

mutual

theorem eraseRename (A : ImplicitType) (ρ : Nat → Nat) :
  (A.rename ρ).erase = A.erase.rename ρ
:= by {
  cases A with
  | int => rfl
  | var α => rfl
  | arrow A B => exact congr (congrArg CoreType.arrow (eraseRename A ρ)) (eraseRename B ρ)
  | implicitArrow A B => exact congr (congrArg CoreType.arrow (eraseRename A ρ)) (eraseRename B ρ)
  | all A => exact congrArg CoreType.all (eraseRename A (Renaming.lift ρ))
  | record R => exact congrArg CoreType.record (eraseRenameFields R ρ)
}

theorem eraseRenameFields (R : List (Label × ImplicitType)) (ρ : Nat → Nat) :
  eraseFields (renameFields ρ R) = CoreType.renameFields ρ (eraseFields R)
:= by {
  cases R with
  | nil => rfl
  | cons r R =>
    {
      rcases r with ⟨ℓ, A⟩
      change (ℓ, (A.rename ρ).erase) :: eraseFields (renameFields ρ R)
        = (ℓ, A.erase.rename ρ) :: CoreType.renameFields ρ (eraseFields R)
      rw [eraseRename A ρ, eraseRenameFields R ρ]
    }
}

end

mutual

theorem eraseSubst (A : ImplicitType) (σ : Nat → ImplicitType) (τ : Nat → CoreType)
  (h : ∀ α, (σ α).erase = τ α) :
  (A.subst σ).erase = A.erase.subst τ
:= by {
  cases A with
  | int => rfl
  | var α => exact h α
  | arrow A B => exact congr (congrArg CoreType.arrow (eraseSubst A σ τ h)) (eraseSubst B σ τ h)
  | implicitArrow A B => exact congr (congrArg CoreType.arrow (eraseSubst A σ τ h)) (eraseSubst B σ τ h)
  | all A =>
    {
      apply congrArg CoreType.all
      apply eraseSubst A (liftSubst σ) (CoreType.liftSubst τ)
      intro α
      cases α with
      | zero => rfl
      | succ α => exact (eraseRename (σ α) Nat.succ).trans (congrArg (CoreType.rename Nat.succ) (h α))
    }
  | record R => exact congrArg CoreType.record (eraseSubstFields R σ τ h)
}

theorem eraseSubstFields (R : List (Label × ImplicitType)) (σ : Nat → ImplicitType) (τ : Nat → CoreType)
  (h : ∀ α, (σ α).erase = τ α) :
  eraseFields (substFields σ R) = CoreType.substFields τ (eraseFields R)
:= by {
  cases R with
  | nil => rfl
  | cons r R =>
    {
      rcases r with ⟨ℓ, A⟩
      change (ℓ, (A.subst σ).erase) :: eraseFields (substFields σ R)
        = (ℓ, A.erase.subst τ) :: CoreType.substFields τ (eraseFields R)
      rw [eraseSubst A σ τ h, eraseSubstFields R σ τ h]
    }
}

end

theorem eraseInstantiate (A B : ImplicitType) :
  (A.instantiate B).erase = A.erase.instantiate B.erase
:= by {
  apply eraseSubst
  intro α
  cases α <;> rfl
}

theorem eraseFieldsMap (R : List (Label × ImplicitType)) :
  eraseFields R = R.map (fun r => (r.1, r.2.erase))
:= by {
  induction R with
  | nil => rfl
  | cons r R ih => cases r; exact congrArg (List.cons _) ih
}

theorem eraseFieldsLabels (R : List (Label × ImplicitType)) :
  (eraseFields R).map Prod.fst = R.map Prod.fst
:= by {
  induction R with
  | nil => rfl
  | cons r R ih => cases r; exact congrArg (List.cons _) ih
}

theorem memEraseFields {R : List (Label × ImplicitType)} {ℓ : Label} {A : ImplicitType}
  (h : (ℓ, A) ∈ R) : (ℓ, A.erase) ∈ eraseFields R
:= by {
  induction R with
  | nil => cases h
  | cons r R ih =>
    {
      rcases r with ⟨k, B⟩
      cases h with
      | head => exact List.Mem.head ..
      | tail _ h => exact List.Mem.tail _ (ih h)
    }
}

theorem ofMemEraseFields {R : List (Label × ImplicitType)} {ℓ : Label} {T : CoreType}
  (h : (ℓ, T) ∈ eraseFields R) : ∃ A, (ℓ, A) ∈ R ∧ A.erase = T
:= by {
  induction R with
  | nil => cases h
  | cons r R ih =>
    {
      rcases r with ⟨k, B⟩
      cases h with
      | head => exact ⟨B, List.Mem.head .., rfl⟩
      | tail _ h =>
        {
          rcases ih h with ⟨A, hA, heq⟩
          exact ⟨A, List.Mem.tail _ hA, heq⟩
        }
    }
}

end ImplicitType

theorem Canonical.eraseFields {R : List (Label × ImplicitType)} (h : Canonical R) :
  Canonical (ImplicitType.eraseFields R)
:= by {
  induction R with
  | nil => exact .nil
  | cons r R ih =>
    {
      rcases r with ⟨k, B⟩
      cases h with
      | cons hhead htail =>
        {
          apply List.Pairwise.cons
          · intro r hr
            rcases r with ⟨ℓ, T⟩
            rcases ImplicitType.ofMemEraseFields hr with ⟨A, hA, _⟩
            exact hhead (ℓ, A) hA
          · exact ih htail
        }
    }
}

theorem ImplicitWellFormed.erase {q : Nat} {A : ImplicitType} (h : q ⊢ⁱᵀ A) : q ⊢ᶜᵀ A.erase := by {
  induction h with
  | int => exact .int
  | var h => exact .var h
  | arrow _ _ ih₁ ih₂ => exact .arrow ih₁ ih₂
  | implicitArrow _ _ ih₁ ih₂ => exact .arrow ih₁ ih₂
  | all _ ih => exact .all ih
  | record hR _ ih =>
    {
      apply CoreWellFormed.record hR.eraseFields
      intro ℓ T hT
      rcases ImplicitType.ofMemEraseFields hT with ⟨A, hA, rfl⟩
      exact ih ℓ A hA
    }
}

end ImplicitSynthesis
