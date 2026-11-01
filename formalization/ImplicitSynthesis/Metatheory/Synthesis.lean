import ImplicitSynthesis.Synthesis
import ImplicitSynthesis.Metatheory.Resolution

/-! Soundness and completeness of the shared-program constraint model. -/

namespace ImplicitSynthesis
namespace Synthesis

theorem ProgramTyping.decode {q V Δ π ps U} (h : ProgramTyping q V Δ π ps U) : DecodeProgram π ps := by {
  induction h with
  | nil => exact .nil
  | cons _ hd _ _ ih => exact .cons ih hd
}

theorem ProgramTyping.lookup {q V Δ π ps U} (h : ProgramTyping q V Δ π ps U)
  {k A} (hk : Lookup U k A) : ∃ p, Lookup ps k p ∧ (q ∣ Δ ⊢ᵉ p ∶ A)
:= by {
  induction h generalizing k with
  | nil => cases hk
  | cons _ _ hp _ ih => cases hk with
    | here => exact ⟨_, .here, hp⟩
    | there hk =>
      {
        rcases ih hk with ⟨p, hk, hp⟩
        exact ⟨p, .there hk, hp⟩
      }
}

theorem ProgramTyping.lookupType {q V Δ π ps U} (h : ProgramTyping q V Δ π ps U)
  {k p A} (hk : Lookup ps k p) (hp : q ∣ Δ ⊢ᵉ p ∶ A) : Lookup U k A
:= by {
  induction h generalizing k with
  | nil => cases hk
  | cons _ _ hp' _ ih => cases hk with
    | here => cases hp'.unique hp; exact .here
    | there hk => exact .there (ih hk)
}

theorem StepConstraints.argumentsValid {V U A κ τ B}
  (h : StepConstraints V U A κ τ B) : ArgumentsValid V κ
:= by {
  induction h with
  | nil => exact .nil
  | type _ hB ih => exact .type hB ih
  | «instance» _ _ ih => exact .instance ih
}

theorem StepConstraints.sound {q V Δ U A κ τ B ps p}
  (h : StepConstraints V U A κ τ B) (hV : ∀ B, V B → q ⊢ⁱᵀ B)
  (hU : ∀ k A, Lookup U k A → ∃ r, Lookup ps k r ∧ (q ∣ Δ ⊢ᵉ r ∶ A))
  (hp : q ∣ Δ ⊢ᵉ p ∶ A) :
  ∃ r, DecodeSteps ps p κ r ∧ (q ∣ Δ ⊢ᵉ r ∶ B)
:= by {
  induction h with
  | nil => exact ⟨p, .nil, hp⟩
  | type _ hB ih =>
    {
      rcases ih with ⟨r, hd, hr⟩
      exact ⟨_, .type hd, .typeApp hr (hV _ hB)⟩
    }
  | «instance» _ hk ih =>
    {
      rcases ih with ⟨r, hd, hr⟩
      rcases hU _ _ hk with ⟨u, hu, ht⟩
      exact ⟨_, .instance hd hu, .implicitApp hr ht⟩
    }
}

theorem SpineConstraints.sound {q V Δ U s τ A ps}
  (h : SpineConstraints V Δ U s τ A) (hV : ∀ B, V B → q ⊢ⁱᵀ B)
  (hU : ∀ k B, Lookup U k B → ∃ r, Lookup ps k r ∧ (q ∣ Δ ⊢ᵉ r ∶ B)) :
  ∃ p, DecodeSpine ps s p ∧ (q ∣ Δ ⊢ᵉ p ∶ A) ∧ ArgumentsValid V s.arguments
:= by {
  cases h with
  | intro hj h =>
    {
      rcases h.sound hV hU (.ref hj) with ⟨p, hd, hp⟩
      exact ⟨p, .intro hd, hp, h.argumentsValid⟩
    }
}

theorem ProgramConstraints.sound {q V Δ π Τ U}
  (h : ProgramConstraints V Δ π Τ U) (hV : ∀ B, V B → q ⊢ⁱᵀ B) :
  ∃ ps, ProgramTyping q V Δ π ps U
:= by {
  induction h with
  | nil => exact ⟨[], .nil⟩
  | cons _ hs ih =>
    {
      rcases ih with ⟨ps, hπ⟩
      rcases hs.sound hV (fun _ _ hk => hπ.lookup hk) with ⟨p, hd, hp, hv⟩
      exact ⟨p :: ps, .cons hπ hd hp hv⟩
    }
}

theorem DecodeSteps.complete {q V Δ U ps p κ r B}
  (hd : DecodeSteps ps p κ r) (hr : q ∣ Δ ⊢ᵉ r ∶ B) (hv : ArgumentsValid V κ)
  (hU : ∀ k u A, Lookup ps k u → (q ∣ Δ ⊢ᵉ u ∶ A) → Lookup U k A) :
  ∃ A τ, (q ∣ Δ ⊢ᵉ p ∶ A) ∧ StepConstraints V U A κ τ B
:= by {
  induction hd generalizing B with
  | nil => exact ⟨B, [B], hr, .nil⟩
  | type hd ih =>
    {
      cases hr with
      | typeApp hr _ => cases hv with
        | type hB hv =>
          {
            rcases ih hr hv with ⟨A, τ, hp, hτ⟩
            exact ⟨A, _, hp, .type hτ hB⟩
          }
    }
  | «instance» hd hk ih =>
    {
      cases hr with
      | implicitApp hr hu => cases hv with
        | «instance» hv =>
          {
            rcases ih hr hv with ⟨A, τ, hp, hτ⟩
            exact ⟨A, _, hp, .instance hτ (hU _ _ _ hk hu)⟩
          }
    }
}

theorem DecodeSpine.complete {q V Δ U ps s p A}
  (hd : DecodeSpine ps s p) (hp : q ∣ Δ ⊢ᵉ p ∶ A) (hv : ArgumentsValid V s.arguments)
  (hU : ∀ k u B, Lookup ps k u → (q ∣ Δ ⊢ᵉ u ∶ B) → Lookup U k B) :
  ∃ τ, SpineConstraints V Δ U s τ A
:= by {
  cases hd with
  | intro hd =>
    {
      rcases hd.complete hp hv hU with ⟨B, τ, hj, hτ⟩
      cases hj with
      | ref hj => exact ⟨τ, .intro hj hτ⟩
    }
}

theorem ProgramTyping.complete {q V Δ π ps U} (h : ProgramTyping q V Δ π ps U) :
  ∃ Τ, ProgramConstraints V Δ π Τ U
:= by {
  induction h with
  | nil => exact ⟨[], .nil⟩
  | cons hπ hd hp hv ih =>
    {
      rcases ih with ⟨Τ, hΤ⟩
      rcases hd.complete hp hv (fun _ _ _ hk hp => hπ.lookupType hk hp) with ⟨τ, hτ⟩
      exact ⟨τ :: Τ, .cons hΤ hτ⟩
    }
}

theorem Holds.witness {φ μ} (h : Holds φ μ) : Witness φ μ.program := by {
  rcases h with ⟨hπ, hG, hN⟩
  rcases hπ.sound (fun _ h => h.1) with ⟨ps, hp⟩
  exact ⟨ps, μ.types, hp, hG, hN⟩
}

theorem Witness.model {φ π} (h : Witness φ π) : ∃ μ, μ.program = π ∧ Holds φ μ := by {
  rcases h with ⟨ps, U, hπ, hG, hN⟩
  rcases hπ.complete with ⟨Τ, hΤ⟩
  exact ⟨⟨π, Τ, U⟩, rfl, hΤ, hG, hN⟩
}

/-- Exact correspondence for each shared program, preserving the same cost and height bounds. -/
theorem boundedCorrespondence (φ : Query) (π : Program) :
  Witness φ π ↔ ∃ μ, μ.program = π ∧ Holds φ μ
:= by {
  constructor
  · exact Witness.model
  · rintro ⟨μ, rfl, hμ⟩
    exact hμ.witness
}

theorem Holds.typedEvidence {φ μ} (h : Holds φ μ) :
  ∃ ps p, DecodeProgram μ.program ps ∧ Lookup ps 0 p ∧ (φ.depth ∣ φ.instances ⊢ᵉ p ∶ φ.goal)
:= by {
  rcases h.witness with ⟨ps, U, hπ, hG, _⟩
  rcases hπ.lookup hG with ⟨p, hp, ht⟩
  exact ⟨ps, p, hπ.decode, hp, ht⟩
}

theorem satSound [SMT.Oracle Query Model Holds] {φ μ}
  (h : SMT.Oracle.Responds Holds φ (.sat μ)) :
  ∃ ps p, DecodeProgram μ.program ps ∧ Lookup ps 0 p ∧ (φ.depth ∣ φ.instances ⊢ᵉ p ∶ φ.goal)
:= by {
  exact (SMT.Oracle.satSound h).typedEvidence
}

theorem unsatSound [SMT.Oracle Query Model Holds] {φ}
  (h : SMT.Oracle.Responds Holds φ .unsat) : ∀ π, ¬ Witness φ π
:= by {
  intro π hπ
  rcases hπ.model with ⟨μ, _, hμ⟩
  exact SMT.Oracle.unsatSound h μ hμ
}

/-- A satisfying model lowers to a well-typed core term under any typed instance embedding. -/
theorem satCoreTyping [SMT.Oracle Query Model Holds] {φ μ δ H}
  (h : SMT.Oracle.Responds Holds φ (.sat μ)) (hδ : Embeds δ φ.instances H) :
  ∃ ps p t, DecodeProgram μ.program ps ∧ Lookup ps 0 p ∧
    (δ ⊢ p ⇝ᵉ t) ∧ (φ.depth ∣ H ⊢ᶜ t ∶ φ.goal.erase)
:= by {
  rcases satSound h with ⟨ps, p, hd, hp, ht⟩
  rcases LowerEvidence.total δ p with ⟨t, hl⟩
  exact ⟨ps, p, t, hd, hp, hl, hl.preservesTyping ht hδ⟩
}

end Synthesis
end ImplicitSynthesis
