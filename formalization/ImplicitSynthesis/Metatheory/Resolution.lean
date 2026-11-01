import ImplicitSynthesis.Resolution
import ImplicitSynthesis.Metatheory.Types
import ImplicitSynthesis.Metatheory.Lists

/-! Evidence and resolution preserve typing under a typed context embedding. -/

namespace ImplicitSynthesis

/-- Each source binding is mapped to a target binding of its erased type. -/
def Embeds (γ : Nat → Nat) (Γ : List ImplicitType) (H : List CoreType) : Prop :=
  ∀ x A, Lookup Γ x A → Lookup H (γ x) A.erase

namespace Embeds

theorem weaken {γ Γ H} (h : Embeds γ Γ H) (T : CoreType) : Embeds (Nat.succ ∘ γ) Γ (T :: H) := by {
  intro x A hx
  exact .there (h x A hx)
}

theorem lift {γ Γ H} (h : Embeds γ Γ H) (A : ImplicitType) :
  Embeds (Renaming.lift γ) (A :: Γ) (A.erase :: H)
:= by {
  intro x B hx
  cases hx with
  | here => exact .here
  | there hx => exact .there (h _ B hx)
}

theorem typeBind {γ Γ H} (h : Embeds γ Γ H) :
  Embeds γ (Γ.map (ImplicitType.rename Nat.succ)) (H.map (CoreType.rename Nat.succ))
:= by {
  intro x A hx
  rcases hx.ofMap with ⟨B, hB, rfl⟩
  rw [ImplicitType.eraseRename]
  exact (h x B hB).map (CoreType.rename Nat.succ)
}

theorem empty (γ : Nat → Nat) : Embeds γ [] [] := by { intro x A h; cases h }

end Embeds

theorem LowerEvidence.preservesTyping {q Δ p A δ t H}
  (hp : q ∣ Δ ⊢ᵉ p ∶ A) (hl : δ ⊢ p ⇝ᵉ t) (hδ : Embeds δ Δ H) :
  q ∣ H ⊢ᶜ t ∶ A.erase
:= by {
  induction hp generalizing t with
  | ref h => cases hl; exact .var (hδ _ _ h)
  | typeApp hp hB ih =>
    {
      cases hl with
      | typeApp hl =>
        {
          rw [ImplicitType.eraseInstantiate]
          exact .typeApp (ih hl hδ) hB.erase
        }
    }
  | implicitApp hp hr ih₁ ih₂ =>
    {
      cases hl with
      | implicitApp hl₁ hl₂ => exact .app (ih₁ hl₁ hδ) (ih₂ hl₂ hδ)
    }
}

theorem LowerEvidence.total (δ : Nat → Nat) (p : Evidence) : ∃ t, δ ⊢ p ⇝ᵉ t := by {
  induction p with
  | ref d => exact ⟨_, .ref⟩
  | typeApp p A ih => rcases ih with ⟨t, ht⟩; exact ⟨_, .typeApp ht⟩
  | implicitApp p r ih₁ ih₂ =>
    {
      rcases ih₁ with ⟨t₁, h₁⟩
      rcases ih₂ with ⟨t₂, h₂⟩
      exact ⟨_, .implicitApp h₁ h₂⟩
    }
}

theorem LowerEvidence.deterministic {δ p t₁ t₂}
  (h₁ : δ ⊢ p ⇝ᵉ t₁) (h₂ : δ ⊢ p ⇝ᵉ t₂) : t₁ = t₂
:= by {
  induction h₁ generalizing t₂ with
  | ref => cases h₂; rfl
  | typeApp _ ih => cases h₂ with
    | typeApp h₂ => exact congrArg (fun t => CoreTerm.typeApp t _) (ih h₂)
  | implicitApp _ _ ih₁ ih₂ => cases h₂ with
    | implicitApp h₂ h₃ => exact congr (congrArg CoreTerm.app (ih₁ h₂)) (ih₂ h₃)
}

theorem EvidenceTyping.unique {q Δ p A B}
  (h₁ : q ∣ Δ ⊢ᵉ p ∶ A) (h₂ : q ∣ Δ ⊢ᵉ p ∶ B) : A = B
:= by {
  induction h₁ generalizing B with
  | ref h => cases h₂ with
    | ref h₂ => exact h.unique h₂
  | typeApp _ _ ih => cases h₂ with
    | typeApp h₂ _ => cases ImplicitType.all.inj (ih h₂); rfl
  | implicitApp _ _ ih _ => cases h₂ with
    | implicitApp h₂ _ => exact (ImplicitType.implicitArrow.inj (ih h₂)).2
}

theorem EvidenceTyping.noEmpty {q p A} (h : q ∣ [] ⊢ᵉ p ∶ A) : False := by {
  generalize hΔ : ([] : List ImplicitType) = Δ at h
  induction h with
  | ref h => cases hΔ; cases h
  | typeApp _ _ ih => exact ih hΔ
  | implicitApp _ _ ih _ => exact ih hΔ
}

theorem ImplicitTyping.permuteRecord {q Γ Δ r s A}
  (h : q ∣ Γ ∣ Δ ⊢ⁱ .record r ∶ A) (hπ : r.Perm s) :
  q ∣ Γ ∣ Δ ⊢ⁱ .record s ∶ A
:= by {
  cases h with
  | record hR hkeys hfields =>
    {
      apply ImplicitTyping.record hR ((Lists.permMap Prod.fst hπ).symm.trans hkeys)
      intro ℓ c A hc hA
      exact hfields ℓ c A (Lists.permMem hπ.symm hc) hA
    }
}

theorem CoreTyping.permuteRecord {q Γ r s A}
  (h : q ∣ Γ ⊢ᶜ .record r ∶ A) (hπ : r.Perm s) : q ∣ Γ ⊢ᶜ .record s ∶ A
:= by {
  cases h with
  | record hR hkeys hfields =>
    {
      apply CoreTyping.record hR ((Lists.permMap Prod.fst hπ).symm.trans hkeys)
      intro ℓ t A ht hA
      exact hfields ℓ t A (Lists.permMem hπ.symm ht) hA
    }
}

theorem ResolveFields.labels {𝒮 q Δ γ δ R S} (h : ResolveFields 𝒮 q Δ γ δ R S) :
  R.map Prod.fst = S.map Prod.fst
:= by {
  induction R generalizing S with
  | nil => cases h; rfl
  | cons r R ih => cases h with
    | cons _ hR => exact congrArg (List.cons _) (ih hR)
}

theorem ResolveFields.preimage {𝒮 q Δ γ δ R S ℓ t}
  (h : ResolveFields 𝒮 q Δ γ δ R S) (ht : (ℓ, t) ∈ S) :
  ∃ c, (ℓ, c) ∈ R ∧ 𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c ⇝ t
:= by {
  induction R generalizing S with
  | nil => cases h; cases ht
  | cons r R ih =>
    {
      cases h with
      | cons hc hR => cases ht with
        | head => exact ⟨_, List.Mem.head .., hc⟩
        | tail _ ht =>
          {
            rcases ih hR ht with ⟨c, hc, hr⟩
            exact ⟨c, List.Mem.tail _ hc, hr⟩
          }
    }
}

/-- Every successful resolution of a typed implicit term has the erased type. -/
theorem Resolves.preservesTyping {𝒮 q Γ Δ c A γ δ t H}
  (hc : q ∣ Γ ∣ Δ ⊢ⁱ c ∶ A) (hr : 𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c ⇝ t)
  (hγ : Embeds γ Γ H) (hδ : Embeds δ Δ H) :
  q ∣ H ⊢ᶜ t ∶ A.erase
:= by {
  induction hc generalizing γ δ t H with
  | var h => cases hr; exact .var (hγ _ _ h)
  | «instance» h => cases hr; exact .var (hδ _ _ h)
  | int => cases hr; exact .int
  | lam hA _ ih => cases hr with
    | lam hr => exact .lam hA.erase (ih hr (hγ.lift _) (hδ.weaken _))
  | implicitLam hA _ ih => cases hr with
    | implicitLam hr => exact .lam hA.erase (ih hr (hγ.weaken _) (hδ.lift _))
  | app _ _ ih₁ ih₂ => cases hr with
    | app hr₁ hr₂ => exact .app (ih₁ hr₁ hγ hδ) (ih₂ hr₂ hγ hδ)
  | implicitApp _ _ ih₁ ih₂ => cases hr with
    | implicitApp hr₁ hr₂ => exact .app (ih₁ hr₁ hγ hδ) (ih₂ hr₂ hγ hδ)
  | typeLam _ ih => cases hr with
    | typeLam hr => exact .typeLam (ih hr hγ.typeBind hδ.typeBind)
  | typeApp _ hB ih =>
    {
      cases hr with
      | typeApp hr =>
        {
          rw [ImplicitType.eraseInstantiate]
          exact .typeApp (ih hr hγ hδ) hB.erase
        }
    }
  | letIn _ _ ih₁ ih₂ => cases hr with
    | letIn hr₁ hr₂ => exact .letIn (ih₁ hr₁ hγ hδ) (ih₂ hr₂ (hγ.lift _) (hδ.weaken _))
  | instanceLet _ _ ih₁ ih₂ => cases hr with
    | instanceLet hr₁ hr₂ => exact .letIn (ih₁ hr₁ hγ hδ) (ih₂ hr₂ (hγ.weaken _) (hδ.lift _))
  | fix hA _ ih => cases hr with
    | fix hr => exact .fix hA.erase (ih hr (hγ.lift _) (hδ.weaken _))
  | need _ => cases hr with
    | need _ hp hl => exact hl.preservesTyping hp hδ
  | record hR hkeys _ ih =>
    {
      cases hr with
      | record hr =>
        {
          apply CoreTyping.record hR.erase
          · rw [← hr.labels, ImplicitType.eraseFieldsLabels]
            exact hkeys
          · intro ℓ t T ht hT
            rcases hr.preimage ht with ⟨c, hc, hr⟩
            rcases ImplicitType.ofMemEraseFields hT with ⟨A, hA, rfl⟩
            exact ih ℓ c A hc hA hr hγ hδ
        }
    }
  | proj _ hA ih => cases hr with
    | proj hr => exact .proj (ih hr hγ hδ) (ImplicitType.memEraseFields hA)
}

theorem Resolves.closedPreservesTyping {𝒮 c A t}
  (hc : 0 ∣ [] ∣ [] ⊢ⁱ c ∶ A) (hr : 𝒮 ∣ 0 ∣ [] ∣ id ∣ id ⊢ c ⇝ t) :
  0 ∣ [] ⊢ᶜ t ∶ A.erase
:= by {
  exact hr.preservesTyping hc (Embeds.empty id) (Embeds.empty id)
}

end ImplicitSynthesis
