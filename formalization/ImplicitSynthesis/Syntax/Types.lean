import Std

/-!
Types use de Bruijn indices. A well-formed record type stores its finite map
in increasing label order. This is a canonical representation of an unordered
map, not a restriction on the order of fields in record terms.
-/

namespace ImplicitSynthesis

abbrev Label := Nat

inductive ImplicitType where
  | int
  | var (α : Nat)
  | arrow (A B : ImplicitType)
  | implicitArrow (A B : ImplicitType)
  | all (A : ImplicitType)
  | record (R : List (Label × ImplicitType))
  deriving Repr

inductive CoreType where
  | int
  | var (α : Nat)
  | arrow (A B : CoreType)
  | all (A : CoreType)
  | record (R : List (Label × CoreType))
  deriving Repr

namespace Renaming

def lift (ρ : Nat → Nat) : Nat → Nat
  | 0 => 0
  | α + 1 => ρ α + 1

end Renaming

namespace ImplicitType

mutual
  def rename (ρ : Nat → Nat) : ImplicitType → ImplicitType
    | .int => .int
    | .var α => .var (ρ α)
    | .arrow A B => .arrow (rename ρ A) (rename ρ B)
    | .implicitArrow A B => .implicitArrow (rename ρ A) (rename ρ B)
    | .all A => .all (rename (Renaming.lift ρ) A)
    | .record R => .record (renameFields ρ R)

  def renameFields (ρ : Nat → Nat) : List (Label × ImplicitType) → List (Label × ImplicitType)
    | [] => []
    | (ℓ, A) :: R => (ℓ, rename ρ A) :: renameFields ρ R
end

def liftSubst (σ : Nat → ImplicitType) : Nat → ImplicitType
  | 0 => .var 0
  | α + 1 => (σ α).rename Nat.succ

mutual
  def subst (σ : Nat → ImplicitType) : ImplicitType → ImplicitType
    | .int => .int
    | .var α => σ α
    | .arrow A B => .arrow (subst σ A) (subst σ B)
    | .implicitArrow A B => .implicitArrow (subst σ A) (subst σ B)
    | .all A => .all (subst (liftSubst σ) A)
    | .record R => .record (substFields σ R)

  def substFields (σ : Nat → ImplicitType) : List (Label × ImplicitType) → List (Label × ImplicitType)
    | [] => []
    | (ℓ, A) :: R => (ℓ, subst σ A) :: substFields σ R
end

def instantiate (A B : ImplicitType) : ImplicitType :=
  A.subst fun α => match α with
    | 0 => B
    | β + 1 => .var β

mutual
  def erase : ImplicitType → CoreType
    | .int => .int
    | .var α => .var α
    | .arrow A B => .arrow A.erase B.erase
    | .implicitArrow A B => .arrow A.erase B.erase
    | .all A => .all A.erase
    | .record R => .record (eraseFields R)

  def eraseFields : List (Label × ImplicitType) → List (Label × CoreType)
    | [] => []
    | (ℓ, A) :: R => (ℓ, erase A) :: eraseFields R
end

end ImplicitType

namespace CoreType

mutual
  def rename (ρ : Nat → Nat) : CoreType → CoreType
    | .int => .int
    | .var α => .var (ρ α)
    | .arrow A B => .arrow (rename ρ A) (rename ρ B)
    | .all A => .all (rename (Renaming.lift ρ) A)
    | .record R => .record (renameFields ρ R)

  def renameFields (ρ : Nat → Nat) : List (Label × CoreType) → List (Label × CoreType)
    | [] => []
    | (ℓ, A) :: R => (ℓ, rename ρ A) :: renameFields ρ R
end

def liftSubst (σ : Nat → CoreType) : Nat → CoreType
  | 0 => .var 0
  | α + 1 => (σ α).rename Nat.succ

mutual
  def subst (σ : Nat → CoreType) : CoreType → CoreType
    | .int => .int
    | .var α => σ α
    | .arrow A B => .arrow (subst σ A) (subst σ B)
    | .all A => .all (subst (liftSubst σ) A)
    | .record R => .record (substFields σ R)

  def substFields (σ : Nat → CoreType) : List (Label × CoreType) → List (Label × CoreType)
    | [] => []
    | (ℓ, A) :: R => (ℓ, subst σ A) :: substFields σ R
end

def instantiate (A B : CoreType) : CoreType :=
  A.subst fun α => match α with
    | 0 => B
    | β + 1 => .var β

end CoreType

end ImplicitSynthesis
