# F7 — Zapping resiliente, buscador usable, y la pestaña de deportes

**Estado:** en curso. Nace del feedback del dueño tras instalar el addon en la TV real
(`192.168.18.6`, ABI `armeabi-v7a`) con la build `dd905bac0` de `ext/livetv`.
**Rama:** `nuvioes`. **Punto de partida:** commit `dd905bac0`.

---

## Contexto

La TV quedó configurada desde cero (keystore nueva → desinstalado lo viejo con OK del dueño)
y con el addon propio instalado y funcionando. Los problemas del emulador no se reprodujeron
en la TV. El dueño reporta:

1. **Zapping se rompe ante canal caído** — si el siguiente canal da error o no emite, el zapping
   deja de funcionar. No es comportamiento natural de un TV.
2. **Buscador**: el texto no está centrado y falta una (X) para borrar rápido.
3. **Addon (kino-light-addon)**: eliminar canales que no funcionan.
4. **Pestaña de deportes**: ver los partidos como Apple TV "Partidos" (tarjetas por partido:
   fecha/hora, colores y logos de equipos). Los partidos tienen múltiples fuentes → qué reproductor.
   Screenshot de referencia: Apple TV app, fila "Partidos".

## Decisiones de diseño

### D1 — Zapping resiliente (recomendación aceptada por defecto hasta reclamo)

El zapping es la actividad primaria de un canal lineal: **las flechas arriba/abajo tienen que
funcionar siempre**, incluso cuando el canal actual falla. Hoy el overlay de error le pasa el
foco a "Reintentar" y el handler deja de consumir direcciones → el D-pad queda cautivo del
botón. Comportamiento natural de TV:

- **El error se informa, no captura**: overlay con el nombre del canal y el motivo, y las
  direcciones siguen zappeando.
- **Auto-skip acotado en resolución**: si el canal al que se zappea no resuelve stream, se salta
  al siguiente (máximo 3 intentos) en vez de dejar al usuario en un callejón.
- "Reintentar" sigue accesible (focusable), pero nunca roba el control del zapping.

### D2 — Pestaña de deportes (por diseñar)

Explorar `kino-light-addon` (rutas de deportes) y las memorias del addon (kino-light-main):
presupuesto upstream, playlists `.m3u8`, `ESS_UPSTREAM_PER_MINUTE`, etc. Propuesta antes de
construir: de dónde salen los partidos, qué card, y qué reproductor para multi-fuente.

---

## Tareas

- [ ] T1 — Zapping resiliente: error no captura el D-pad; auto-skip acotado en resolución.
- [ ] T2 — Buscador: texto centrado + botón (X) de borrado.
- [ ] T3 — Verificación en TV (192.168.18.6) del ciclo completo.
- [ ] T4 — Propuesta de pestaña Partidos (explorar addon + memorias) para discutir con el dueño.
- [ ] T5 — Addon: método para detectar y eliminar canales muertos.

## Registro de trabajo

- T1+T2+T3 — commit `75d588d83`, verificado en TV real (192.168.18.6): zap 421 (negro) → 416
  reproduciendo; buscador con texto centrado y (X) visible; "tlc" → 4 canales.
- T5 — ya existe: `probe-channels.ts` + `live-health.ts` sondean los canales dentro del
  contenedor y el servidor oculta los muertos confirmados en DOS pasadas. Falta correrlo, no
  construirlo.
- T4 — **HECHA** en tres fases delegadas (writer) + verificación del padre:
  - Fase datos: `LiveTvPartition` (rb_ fuera de la lista de canales), `resolveAll` (todas las
    fuentes en orden del addon), 164 tests.
  - Fase UI in-screen: grilla + selector de fuentes + auto-avance acotado por el request.
  - Fase sección propia (decisión del dueño: "en su propia sección, no dentro de tv en vivo"):
    destino `livetv_matches` en el riel lateral, ViewModel compartido por actividad, zapping y
    gaveta inhibidos para un partido. Chip eliminado de la pantalla de TV en vivo.
  - Presupuesto: 5/5 archivos, **74/80 líneas** — techo subido con la decisión del dueño
    documentada en el guardarraíl, NUVIO-ES.md y este doc.

## Lote de feedback 2026-10-01 (segunda ronda del dueño)

| # | Pedido | Estado |
| --- | --- | --- |
| P1/P2 | Partidos agrupados por deporte + barra superior de deportes | writer en curso (cliente) |
| P3 | Activar/desactivar deportes en Settings (como canales) | writer en curso (cliente) |
| P4 | Hora correcta: En vivo / Empieza a las HH:MM con tz del usuario | writer en curso; requiere `released` ISO desde el addon (writer en curso en kino-light-addon; MetaPreview.released ya existe) |
| P5 | Fuentes con nombre real, no "Esencial Sport/Play" | writer en curso: label derivado de `title` (último segmento tras "·"); el addon manda name genérico + title con la fuente |
| P6 | Zapping salta canales sin fuente hasta encontrar reproducible | pendiente (tras el writer de Partidos: comparte ViewModel) |
| P7 | Gaveta: foco automático + flechas libres + foco de vuelta al reproductor | pendiente (idem) |
| P8 | Actores "Algo salió mal" | CAUSA: `TMDB_API_KEY` vacía en el build (local.properties no la tiene). No extraíble de ningún APK local (original, fork, fctv77, MoviePlus — sin key en dex/assets). Requiere que el dueño genere una key gratuita en themoviedb.org y la ponga en local.properties/local.dev.properties |
