import ImplicitSynthesis.Notation

/-! Relational formation and typing. Record terms are checked by label. -/

namespace ImplicitSynthesis

set_option quotPrecheck false

set_option hygiene false in
scoped notation:50 q:51 " ⊢ⁱᵀ " A:51 => ImplicitWellFormed q A
set_option hygiene false in
scoped notation:50 q:51 " ⊢ᶜᵀ " A:51 => CoreWellFormed q A
set_option hygiene false in
scoped notation:50 q:51 " ∣ " Δ:51 " ⊢ᵉ " p:51 " ∶ " A:51 => EvidenceTyping q Δ p A
set_option hygiene false in
scoped notation:50 q:51 " ∣ " Γ:51 " ∣ " Δ:51 " ⊢ⁱ " c:51 " ∶ " A:51 => ImplicitTyping q Γ Δ c A
set_option hygiene false in
scoped notation:50 q:51 " ∣ " Γ:51 " ⊢ᶜ " t:51 " ∶ " A:51 => CoreTyping q Γ t A

def Canonical (R : List (Label × α)) : Prop := R.Pairwise fun a b => a.1 < b.1

inductive Lookup : List α → Nat → α → Prop where

  -- ───────────── L-Here
  -- (A, Γ)[0] = A
  | here : Lookup (A :: Γ) 0 A

  -- Γ[x] = A
  -- ────────────────── L-There
  -- (B, Γ)[x + 1] = A
  | there : Lookup Γ x A → Lookup (B :: Γ) (Nat.succ x) A

inductive ImplicitWellFormed : Nat → ImplicitType → Prop where

  -- ───────────── W-Int
  -- q ⊢ Int type
  | int : q ⊢ⁱᵀ ℤ

  -- α < q
  -- ───────────── W-Var
  -- q ⊢ α type
  | var : α < q → q ⊢ⁱᵀ #α

  -- q ⊢ A type    q ⊢ B type
  -- ───────────────────────── W-Arrow
  -- q ⊢ A → B type
  | arrow : (q ⊢ⁱᵀ A) → (q ⊢ⁱᵀ B) → q ⊢ⁱᵀ A ⟶ B

  -- q ⊢ A type    q ⊢ B type
  -- ───────────────────────── W-Implicit-Arrow
  -- q ⊢ A ⇒ B type
  | implicitArrow : (q ⊢ⁱᵀ A) → (q ⊢ⁱᵀ B) → q ⊢ⁱᵀ A ⇒ B

  -- q + 1 ⊢ A type
  -- ───────────────── W-All
  -- q ⊢ ∀α. A type
  | all : (q + 1 ⊢ⁱᵀ A) → q ⊢ⁱᵀ ∀. A

  -- R represents a finite map    q ⊢ Aℓ type for every field ℓ
  -- ──────────────────────────────────────────────────────── W-Record
  -- q ⊢ {ℓ : Aℓ} type
  | record :
      Canonical R →
      (∀ ℓ A, (ℓ, A) ∈ R → q ⊢ⁱᵀ A) →
      q ⊢ⁱᵀ {R}

inductive CoreWellFormed : Nat → CoreType → Prop where

  -- ───────────── W-Int
  -- q ⊢ Int type
  | int : q ⊢ᶜᵀ ℤ

  -- α < q
  -- ───────────── W-Var
  -- q ⊢ α type
  | var : α < q → q ⊢ᶜᵀ #α

  -- q ⊢ A type    q ⊢ B type
  -- ───────────────────────── W-Arrow
  -- q ⊢ A → B type
  | arrow : (q ⊢ᶜᵀ A) → (q ⊢ᶜᵀ B) → q ⊢ᶜᵀ A ⟶ B

  -- q + 1 ⊢ A type
  -- ───────────────── W-All
  -- q ⊢ ∀α. A type
  | all : (q + 1 ⊢ᶜᵀ A) → q ⊢ᶜᵀ ∀. A

  -- R represents a finite map    q ⊢ Aℓ type for every field ℓ
  -- ──────────────────────────────────────────────────────── W-Record
  -- q ⊢ {ℓ : Aℓ} type
  | record :
      Canonical R →
      (∀ ℓ A, (ℓ, A) ∈ R → q ⊢ᶜᵀ A) →
      q ⊢ᶜᵀ {R}

inductive EvidenceTyping : Nat → List ImplicitType → Evidence → ImplicitType → Prop where

  -- d : A ∈ Δ
  -- ───────────── P-Instance
  -- q; Δ ⊢ev d : A
  | ref : Lookup Δ d A → q ∣ Δ ⊢ᵉ $[d] ∶ A

  -- q; Δ ⊢ev p : ∀α. A    q ⊢ B type
  -- ───────────────────────────────── P-Type-App
  -- q; Δ ⊢ev p[B] : A[B/α]
  | typeApp :
      (q ∣ Δ ⊢ᵉ p ∶ ∀. A) →
      (q ⊢ⁱᵀ B) →
      q ∣ Δ ⊢ᵉ p⟦B⟧ ∶ A.instantiate B

  -- q; Δ ⊢ev p : A ⇒ B    q; Δ ⊢ev r : A
  -- ───────────────────────────────────── P-Implicit-App
  -- q; Δ ⊢ev p⟨r⟩ : B
  | implicitApp :
      (q ∣ Δ ⊢ᵉ p ∶ A ⇒ B) →
      (q ∣ Δ ⊢ᵉ r ∶ A) →
      q ∣ Δ ⊢ᵉ p⟨r⟩ ∶ B

inductive ImplicitTyping :
  Nat → List ImplicitType → List ImplicitType → ImplicitTerm → ImplicitType → Prop
where

  -- x : A ∈ Γ
  -- ───────────────── T-Var
  -- q; Γ; Δ ⊢ x : A
  | var : Lookup Γ x A → q ∣ Γ ∣ Δ ⊢ⁱ $[x] ∶ A

  -- d : A ∈ Δ
  -- ─────────────────── T-Instance
  -- q; Γ; Δ ⊢ d : A
  | instance : Lookup Δ d A → q ∣ Γ ∣ Δ ⊢ⁱ ι[d] ∶ A

  -- ───────────────── T-Int
  -- q; Γ; Δ ⊢ n : Int
  | int : q ∣ Γ ∣ Δ ⊢ⁱ ⌜n⌝ ∶ ℤ

  -- q ⊢ A type    q; Γ,x:A; Δ ⊢ c : B
  -- ───────────────────────────────── T-Lambda
  -- q; Γ; Δ ⊢ λ(x:A). c : A → B
  | lam : (q ⊢ⁱᵀ A) → (q ∣ A :: Γ ∣ Δ ⊢ⁱ c ∶ B) → q ∣ Γ ∣ Δ ⊢ⁱ λ[A]. c ∶ A ⟶ B

  -- q ⊢ A type    q; Γ; d:A,Δ ⊢ c : B
  -- ───────────────────────────────── T-Implicit-Lambda
  -- q; Γ; Δ ⊢ λ⟨d:A⟩. c : A ⇒ B
  | implicitLam :
      (q ⊢ⁱᵀ A) → (q ∣ Γ ∣ A :: Δ ⊢ⁱ c ∶ B) → q ∣ Γ ∣ Δ ⊢ⁱ λ⟨A⟩. c ∶ A ⇒ B

  -- q; Γ; Δ ⊢ c₁ : A → B    q; Γ; Δ ⊢ c₂ : A
  -- ──────────────────────────────────────── T-App
  -- q; Γ; Δ ⊢ c₁ c₂ : B
  | app : (q ∣ Γ ∣ Δ ⊢ⁱ c₁ ∶ A ⟶ B) → (q ∣ Γ ∣ Δ ⊢ⁱ c₂ ∶ A) → q ∣ Γ ∣ Δ ⊢ⁱ c₁ ⋅ c₂ ∶ B

  -- q; Γ; Δ ⊢ c₁ : A ⇒ B    q; Γ; Δ ⊢ c₂ : A
  -- ──────────────────────────────────────── T-Implicit-App
  -- q; Γ; Δ ⊢ c₁⟨c₂⟩ : B
  | implicitApp :
      (q ∣ Γ ∣ Δ ⊢ⁱ c₁ ∶ A ⇒ B) →
      (q ∣ Γ ∣ Δ ⊢ⁱ c₂ ∶ A) →
      q ∣ Γ ∣ Δ ⊢ⁱ c₁⟨c₂⟩ ∶ B

  -- q+1; ↑Γ; ↑Δ ⊢ c : A
  -- ───────────────────────── T-Type-Lambda
  -- q; Γ; Δ ⊢ Λα. c : ∀α. A
  | typeLam :
      (q + 1 ∣ Γ.map (ImplicitType.rename Nat.succ) ∣ Δ.map (ImplicitType.rename Nat.succ) ⊢ⁱ c ∶ A) →
      q ∣ Γ ∣ Δ ⊢ⁱ Λ. c ∶ ∀. A

  -- q; Γ; Δ ⊢ c : ∀α. A    q ⊢ B type
  -- ───────────────────────────────── T-Type-App
  -- q; Γ; Δ ⊢ c[B] : A[B/α]
  | typeApp :
      (q ∣ Γ ∣ Δ ⊢ⁱ c ∶ ∀. A) → (q ⊢ⁱᵀ B) → q ∣ Γ ∣ Δ ⊢ⁱ c⟦B⟧ ∶ A.instantiate B

  -- q; Γ; Δ ⊢ c₁ : A    q; Γ,x:A; Δ ⊢ c₂ : B
  -- ───────────────────────────────────────── T-Let
  -- q; Γ; Δ ⊢ let x:A = c₁ in c₂ : B
  | letIn : (q ∣ Γ ∣ Δ ⊢ⁱ c₁ ∶ A) → (q ∣ A :: Γ ∣ Δ ⊢ⁱ c₂ ∶ B) → q ∣ Γ ∣ Δ ⊢ⁱ let[A] c₁ in c₂ ∶ B

  -- q; Γ; Δ ⊢ c₁ : A    q; Γ; d:A,Δ ⊢ c₂ : B
  -- ───────────────────────────────────────── T-Instance-Let
  -- q; Γ; Δ ⊢ instance d:A = c₁ in c₂ : B
  | instanceLet :
      (q ∣ Γ ∣ Δ ⊢ⁱ c₁ ∶ A) → (q ∣ Γ ∣ A :: Δ ⊢ⁱ c₂ ∶ B) → q ∣ Γ ∣ Δ ⊢ⁱ instance[A] c₁ in c₂ ∶ B

  -- q ⊢ A type    q; Γ,x:A; Δ ⊢ c : A
  -- ───────────────────────────────── T-Fix
  -- q; Γ; Δ ⊢ fix x:A. c : A
  | fix : (q ⊢ⁱᵀ A) → (q ∣ A :: Γ ∣ Δ ⊢ⁱ c ∶ A) → q ∣ Γ ∣ Δ ⊢ⁱ fix[A]. c ∶ A

  -- q ⊢ A type
  -- ───────────────── T-Need
  -- q; Γ; Δ ⊢ ?A : A
  | need : (q ⊢ⁱᵀ A) → q ∣ Γ ∣ Δ ⊢ⁱ ?[A] ∶ A

  -- q ⊢ {ℓ:Aℓ} type    r has exactly those labels    q; Γ; Δ ⊢ rℓ : Aℓ
  -- ─────────────────────────────────────────────────────────────── T-Record
  -- q; Γ; Δ ⊢ {ℓ=rℓ} : {ℓ:Aℓ}
  | record :
      (q ⊢ⁱᵀ {R}) →
      (r.map Prod.fst).Perm (R.map Prod.fst) →
      (∀ ℓ c A, (ℓ, c) ∈ r → (ℓ, A) ∈ R → q ∣ Γ ∣ Δ ⊢ⁱ c ∶ A) →
      q ∣ Γ ∣ Δ ⊢ⁱ {r} ∶ {R}

  -- q; Γ; Δ ⊢ c : {ℓ:Aℓ}    ℓ:A ∈ R
  -- ───────────────────────────────── T-Project
  -- q; Γ; Δ ⊢ c.ℓ : A
  | proj : (q ∣ Γ ∣ Δ ⊢ⁱ c ∶ {R}) → (ℓ, A) ∈ R → q ∣ Γ ∣ Δ ⊢ⁱ c.[ℓ] ∶ A

inductive CoreTyping : Nat → List CoreType → CoreTerm → CoreType → Prop where

  -- x : A ∈ Γ
  -- ─────────────── T-Var
  -- q; Γ ⊢ x : A
  | var : Lookup Γ x A → q ∣ Γ ⊢ᶜ $[x] ∶ A

  -- ───────────────── T-Int
  -- q; Γ ⊢ n : Int
  | int : q ∣ Γ ⊢ᶜ ⌜n⌝ ∶ ℤ

  -- q ⊢ A type    q; Γ,x:A ⊢ t : B
  -- ────────────────────────────── T-Lambda
  -- q; Γ ⊢ λ(x:A). t : A → B
  | lam : (q ⊢ᶜᵀ A) → (q ∣ A :: Γ ⊢ᶜ t ∶ B) → q ∣ Γ ⊢ᶜ λ[A]. t ∶ A ⟶ B

  -- q; Γ ⊢ t₁ : A → B    q; Γ ⊢ t₂ : A
  -- ────────────────────────────────── T-App
  -- q; Γ ⊢ t₁ t₂ : B
  | app : (q ∣ Γ ⊢ᶜ t₁ ∶ A ⟶ B) → (q ∣ Γ ⊢ᶜ t₂ ∶ A) → q ∣ Γ ⊢ᶜ t₁ ⋅ t₂ ∶ B

  -- q+1; ↑Γ ⊢ t : A
  -- ────────────────────── T-Type-Lambda
  -- q; Γ ⊢ Λα. t : ∀α. A
  | typeLam : (q + 1 ∣ Γ.map (CoreType.rename Nat.succ) ⊢ᶜ t ∶ A) → q ∣ Γ ⊢ᶜ Λ. t ∶ ∀. A

  -- q; Γ ⊢ t : ∀α. A    q ⊢ B type
  -- ────────────────────────────── T-Type-App
  -- q; Γ ⊢ t[B] : A[B/α]
  | typeApp : (q ∣ Γ ⊢ᶜ t ∶ ∀. A) → (q ⊢ᶜᵀ B) → q ∣ Γ ⊢ᶜ t⟦B⟧ ∶ A.instantiate B

  -- q; Γ ⊢ t₁ : A    q; Γ,x:A ⊢ t₂ : B
  -- ────────────────────────────────── T-Let
  -- q; Γ ⊢ let x = t₁ in t₂ : B
  | letIn : (q ∣ Γ ⊢ᶜ t₁ ∶ A) → (q ∣ A :: Γ ⊢ᶜ t₂ ∶ B) → q ∣ Γ ⊢ᶜ let t₁ in t₂ ∶ B

  -- q ⊢ A type    q; Γ,x:A ⊢ t : A
  -- ────────────────────────────── T-Fix
  -- q; Γ ⊢ fix x:A. t : A
  | fix : (q ⊢ᶜᵀ A) → (q ∣ A :: Γ ⊢ᶜ t ∶ A) → q ∣ Γ ⊢ᶜ fix[A]. t ∶ A

  -- q ⊢ {ℓ:Aℓ} type    r has exactly those labels    q; Γ ⊢ rℓ : Aℓ
  -- ───────────────────────────────────────────────────────────── T-Record
  -- q; Γ ⊢ {ℓ=rℓ} : {ℓ:Aℓ}
  | record :
      (q ⊢ᶜᵀ {R}) →
      (r.map Prod.fst).Perm (R.map Prod.fst) →
      (∀ ℓ t A, (ℓ, t) ∈ r → (ℓ, A) ∈ R → q ∣ Γ ⊢ᶜ t ∶ A) →
      q ∣ Γ ⊢ᶜ {r} ∶ {R}

  -- q; Γ ⊢ t : {ℓ:Aℓ}    ℓ:A ∈ R
  -- ───────────────────────────── T-Project
  -- q; Γ ⊢ t.ℓ : A
  | proj : (q ∣ Γ ⊢ᶜ t ∶ {R}) → (ℓ, A) ∈ R → q ∣ Γ ⊢ᶜ t.[ℓ] ∶ A

end ImplicitSynthesis
