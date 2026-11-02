import Std

macro mods:declModifiers "lemma " id:declId sig:declSig val:declVal : command =>
  `($mods:declModifiers theorem $id:declId $sig:declSig $val:declVal)
