# patches/

Parches re-aplicables de esta feature.

- `f6-focus-restore-DRAFT.patch` — **borrador, NO aplicado y NO verificado.** Restaura el foco al canal
  donde estaba el usuario en vez de la fila 1, con requesters por `stableKey` (nunca por índice: es el
  `UI-11` del fork) y `scrollToItem` antes de pedir foco, porque una fila no compuesta no tiene nodo de
  foco. Se generó con `git diff` y se revirtió del árbol porque la sesión terminó antes de compilarlo.

  **Antes de aplicarlo**: revisarlo, aplicarlo con `git apply`, compilar, y **verificarlo en dispositivo**
  con `nuvioes/focus-probe.py` — entrar al canal 8, ver, volver, y comprobar que la banda 8 queda
  resaltada y no la 1.
