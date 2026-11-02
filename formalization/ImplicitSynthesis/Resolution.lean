import ImplicitSynthesis.Typing

/-!
Resolution replaces requests with checked evidence and embeds the two source
variable spaces into one target space. The relation does not assume target typing.
-/

namespace ImplicitSynthesis

abbrev Search := Nat → List ImplicitType → ImplicitType → Evidence → Prop

set_option quotPrecheck false

set_option hygiene false in
scoped notation:50 δ:51 " ⊢ " p:51 " ⇝ᵉ " t:51 => LowerEvidence δ p t

set_option hygiene false in
scoped notation:50 𝒮:51 " ∣ " q:51 " ∣ " Δ:51 " ∣ " γ:51 " ∣ " δ:51 " ⊢ " c:51 " ⇝ " t:51 =>
  Resolves 𝒮 q Δ γ δ c t

inductive LowerEvidence : (Nat → Nat) → Evidence → CoreTerm → Prop where

  -- ───────────────── E-Ref
  -- δ ⊢ d ⇝ δ(d)
  | ref : δ ⊢ $[d] ⇝ᵉ $[δ d]

  -- δ ⊢ p ⇝ t
  -- ───────────────────────── E-Type-App
  -- δ ⊢ p[A] ⇝ t[|A|]
  | typeApp : (δ ⊢ p ⇝ᵉ t) → δ ⊢ p⟦A⟧ ⇝ᵉ t⟦A.erase⟧

  -- δ ⊢ p ⇝ t₁    δ ⊢ r ⇝ t₂
  -- ───────────────────────── E-Implicit-App
  -- δ ⊢ p⟨r⟩ ⇝ t₁ t₂
  | implicitApp : (δ ⊢ p ⇝ᵉ t₁) → (δ ⊢ r ⇝ᵉ t₂) → δ ⊢ p⟨r⟩ ⇝ᵉ t₁ ⋅ t₂

mutual

inductive Resolves :
  Search → Nat → List ImplicitType → (Nat → Nat) → (Nat → Nat) → ImplicitTerm → CoreTerm → Prop where

  -- ─────────────────── R-Var
  -- γ; δ ⊢ x ⇝ γ(x)
  | var : 𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ $[x] ⇝ $[γ x]

  -- ─────────────────────── R-Instance
  -- γ; δ ⊢ d ⇝ δ(d)
  | instance : 𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ ι[d] ⇝ $[δ d]

  -- ───────────── R-Int
  -- γ; δ ⊢ n ⇝ n
  | int : 𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ ⌜n⌝ ⇝ ⌜n⌝

  -- lift γ; ↑δ ⊢ c ⇝ t
  -- ───────────────────────────────── R-Lambda
  -- γ; δ ⊢ λ(x:A). c ⇝ λ(x:|A|). t
  | lam :
      (𝒮 ∣ q ∣ Δ ∣ Renaming.lift γ ∣ Nat.succ ∘ δ ⊢ c ⇝ t) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ λ[A]. c ⇝ λ[A.erase]. t

  -- ↑γ; lift δ ⊢ c ⇝ t    Δ extended by d:A
  -- ────────────────────────────────────── R-Implicit-Lambda
  -- γ; δ ⊢ λ⟨d:A⟩. c ⇝ λ(d:|A|). t
  | implicitLam :
      (𝒮 ∣ q ∣ A :: Δ ∣ Nat.succ ∘ γ ∣ Renaming.lift δ ⊢ c ⇝ t) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ λ⟨A⟩. c ⇝ λ[A.erase]. t

  -- γ; δ ⊢ c₁ ⇝ t₁    γ; δ ⊢ c₂ ⇝ t₂
  -- ───────────────────────────────── R-App
  -- γ; δ ⊢ c₁ c₂ ⇝ t₁ t₂
  | app :
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₁ ⇝ t₁) →
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₂ ⇝ t₂) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₁ ⋅ c₂ ⇝ t₁ ⋅ t₂

  -- γ; δ ⊢ c₁ ⇝ t₁    γ; δ ⊢ c₂ ⇝ t₂
  -- ───────────────────────────────── R-Implicit-App
  -- γ; δ ⊢ c₁⟨c₂⟩ ⇝ t₁ t₂
  | implicitApp :
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₁ ⇝ t₁) →
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₂ ⇝ t₂) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₁⟨c₂⟩ ⇝ t₁ ⋅ t₂

  -- q+1; ↑Δ; γ; δ ⊢ c ⇝ t
  -- ───────────────────────── R-Type-Lambda
  -- q; Δ; γ; δ ⊢ Λα. c ⇝ Λα. t
  | typeLam :
      (𝒮 ∣ q + 1 ∣ Δ.map (ImplicitType.rename Nat.succ) ∣ γ ∣ δ ⊢ c ⇝ t) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ Λ. c ⇝ Λ. t

  -- γ; δ ⊢ c ⇝ t
  -- ────────────────────────── R-Type-App
  -- γ; δ ⊢ c[A] ⇝ t[|A|]
  | typeApp :
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c ⇝ t) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c⟦A⟧ ⇝ t⟦A.erase⟧

  -- γ; δ ⊢ c₁ ⇝ t₁    lift γ; ↑δ ⊢ c₂ ⇝ t₂
  -- ───────────────────────────────────────────── R-Let
  -- γ; δ ⊢ let x:A = c₁ in c₂ ⇝ let x = t₁ in t₂
  | letIn :
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₁ ⇝ t₁) →
      (𝒮 ∣ q ∣ Δ ∣ Renaming.lift γ ∣ Nat.succ ∘ δ ⊢ c₂ ⇝ t₂) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ let[A] c₁ in c₂ ⇝ let t₁ in t₂

  -- γ; δ ⊢ c₁ ⇝ t₁    ↑γ; lift δ ⊢ c₂ ⇝ t₂    Δ extended by d:A
  -- ───────────────────────────────────────────────────────── R-Instance-Let
  -- γ; δ ⊢ instance d:A = c₁ in c₂ ⇝ let d = t₁ in t₂
  | instanceLet :
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c₁ ⇝ t₁) →
      (𝒮 ∣ q ∣ A :: Δ ∣ Nat.succ ∘ γ ∣ Renaming.lift δ ⊢ c₂ ⇝ t₂) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ instance[A] c₁ in c₂ ⇝ let t₁ in t₂

  -- lift γ; ↑δ ⊢ c ⇝ t
  -- ─────────────────────────────── R-Fix
  -- γ; δ ⊢ fix x:A. c ⇝ fix x:|A|. t
  | fix :
      (𝒮 ∣ q ∣ Δ ∣ Renaming.lift γ ∣ Nat.succ ∘ δ ⊢ c ⇝ t) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ fix[A]. c ⇝ fix[A.erase]. t

  -- 𝒮(q,Δ,A,p)    q; Δ ⊢ev p:A    δ ⊢ p ⇝ t
  -- ─────────────────────────────────────── R-Need
  -- γ; δ ⊢ ?A ⇝ t
  | need :
      𝒮 q Δ A p →
      (q ∣ Δ ⊢ᵉ p ∶ A) →
      (δ ⊢ p ⇝ᵉ t) →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ ?[A] ⇝ t

  -- γ; δ ⊢ cℓ ⇝ tℓ for each field ℓ
  -- ───────────────────────────────── R-Record
  -- γ; δ ⊢ {ℓ=cℓ} ⇝ {ℓ=tℓ}
  | record :
      ResolveFields 𝒮 q Δ γ δ R S →
      𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ {R} ⇝ {S}

  -- γ; δ ⊢ c ⇝ t
  -- ───────────────── R-Project
  -- γ; δ ⊢ c.ℓ ⇝ t.ℓ
  | proj : (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c ⇝ t) → 𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c.[ℓ] ⇝ t.[ℓ]

inductive ResolveFields :
  Search → Nat → List ImplicitType → (Nat → Nat) → (Nat → Nat) →
  List (Label × ImplicitTerm) → List (Label × CoreTerm) → Prop where

  -- ───────────────── R-Fields-Empty
  -- γ; δ ⊢ [] ⇝ []
  | nil : ResolveFields 𝒮 q Δ γ δ [] []

  -- γ; δ ⊢ c ⇝ t    γ; δ ⊢ R ⇝ S
  -- ───────────────────────────── R-Fields-Cons
  -- γ; δ ⊢ (ℓ,c)::R ⇝ (ℓ,t)::S
  | cons :
      (𝒮 ∣ q ∣ Δ ∣ γ ∣ δ ⊢ c ⇝ t) →
      ResolveFields 𝒮 q Δ γ δ R S →
      ResolveFields 𝒮 q Δ γ δ ((ℓ, c) :: R) ((ℓ, t) :: S)

end

end ImplicitSynthesis
