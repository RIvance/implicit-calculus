import ImplicitSynthesis.Syntax.Terms

/-! Scoped notation for indexed types, terms, and evidence. -/

namespace ImplicitSynthesis

scoped notation "ℤ" => ImplicitType.int
scoped notation "ℤ" => CoreType.int
scoped notation:max "#" α:max => ImplicitType.var α
scoped notation:max "#" α:max => CoreType.var α
scoped infixr:60 " ⟶ " => ImplicitType.arrow
scoped infixr:60 " ⟶ " => CoreType.arrow
scoped infixr:60 " ⇒ " => ImplicitType.implicitArrow
scoped notation:60 "∀. " A:60 => ImplicitType.all A
scoped notation:60 "∀. " A:60 => CoreType.all A
scoped notation:max "{" R "}" => ImplicitType.record R
scoped notation:max "{" R "}" => CoreType.record R

scoped notation:max "$[" x "]" => ImplicitTerm.var x
scoped notation:max "$[" x "]" => CoreTerm.var x
scoped notation:max "$[" d "]" => Evidence.ref d
scoped notation:max "ι[" d "]" => ImplicitTerm.instance d
scoped notation:max "⌜" n "⌝" => ImplicitTerm.int n
scoped notation:max "⌜" n "⌝" => CoreTerm.int n
scoped notation:60 "λ[" A "]. " c:60 => ImplicitTerm.lam A c
scoped notation:60 "λ[" A "]. " t:60 => CoreTerm.lam A t
scoped notation:60 "λ⟨" A "⟩. " c:60 => ImplicitTerm.implicitLam A c
scoped infixl:70 " ⋅ " => ImplicitTerm.app
scoped infixl:70 " ⋅ " => CoreTerm.app
scoped notation:80 p:80 "⟨" r "⟩" => ImplicitTerm.implicitApp p r
scoped notation:80 p:80 "⟨" r "⟩" => Evidence.implicitApp p r
scoped notation:60 "Λ. " c:60 => ImplicitTerm.typeLam c
scoped notation:60 "Λ. " t:60 => CoreTerm.typeLam t
scoped notation:80 c:80 "⟦" A "⟧" => ImplicitTerm.typeApp c A
scoped notation:80 t:80 "⟦" A "⟧" => CoreTerm.typeApp t A
scoped notation:80 p:80 "⟦" A "⟧" => Evidence.typeApp p A
scoped notation:60 "let[" A "] " c₁ " in " c₂:60 => ImplicitTerm.letIn A c₁ c₂
scoped notation:60 "let " t₁ " in " t₂:60 => CoreTerm.letIn t₁ t₂
scoped notation:60 "instance[" A "] " c₁ " in " c₂:60 => ImplicitTerm.instanceLet A c₁ c₂
scoped notation:60 "fix[" A "]. " c:60 => ImplicitTerm.fix A c
scoped notation:60 "fix[" A "]. " t:60 => CoreTerm.fix A t
scoped notation:max "?[" A "]" => ImplicitTerm.need A
scoped notation:max "{" R "}" => ImplicitTerm.record R
scoped notation:max "{" R "}" => CoreTerm.record R
scoped notation:80 c:80 ".[" ℓ "]" => ImplicitTerm.proj c ℓ
scoped notation:80 t:80 ".[" ℓ "]" => CoreTerm.proj t ℓ

end ImplicitSynthesis
