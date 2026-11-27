# Tartarus Breach — Game Design Document & Storyboard

## 1. High Concept
**Tartarus Breach** is a fast-paced, first-person 2.5D arena roguelite built natively for physical-keyboard Android phones (Zinwa Q25/P26/Q27, Unihertz Titan 2 / Titan 2 Elite). Inspired by the kinetic feel of *Doom (1993)* and the wave-draft loop of *Devil Daggers* / *Hades*, players wield mythological Greek artifacts (zero firearms) across 4 Underworld Gates (20 stages total).

## 2. Core Design Pillars for PKB Hardware
- **Zero Thumb Occlusion:** Top 78% is an unobstructed 3D/2.5D arena; bottom 22% is a locked marble-and-bronze status bar aligned directly above the physical QWERTY keys.
- **No Vertical Aiming:** Crosshair stays locked to the horizon with generous horizontal auto-aim magnetism (`4.5°–10.0°` per weapon) and instant vertical auto-aim.
- **2D Billboard Sprites in 3D Space:** Crisp cel-shaded flat-vector sprites rasterized to a startup bitmap atlas allow 30–45 concurrent enemies at 60fps without thermal throttling.
- **2–3 Key Rollover Friendly:** Auto-repeat on `Space`, trackpad/capacitive swipe for yaw turning, and single-tap `[1/2/3]` (or `[A/S/D]`) Boon drafting.

## 3. Mythological Arsenal (Zero Guns)
| Hotkey | Artifact | Role | Behavior |
|---|---|---|---|
| `Q` | **Apollo's Sunbow** | Precision Rapid | Hitscan solar streaks, 0 Favor cost |
| `W` / `U` | **Poseidon's Trident** | Crowd Clear | Wide 7-ray Stygian water wave, high knockback |
| `E` | **Hades' Bident** | Piercing Mid | Dual violet soul-bolts, pierces 3 enemies |
| `R` | **Zeus's Aegis Bolt** | Screen Clear | Chaining lightning across up to 8 targets (35 Favor) |

## 4. 5-Screen Core Storyboard
1. **Screen 1 — Gate Selection (`screen_01_gate_select.png`):** Vertical keyboard-navigable list (`W/S` + `Enter`) of the 4 Underworld Gates over the River Styx, displaying best survival times and total Obols.
2. **Screen 2 — Active Arena Combat (`screen_02_arena_hud.png`):** Top 78% viewport with floating wave timer (`WAVE 04 | 00:54.1 | OBOLS 230`) and subtle circular flank-radar crosshair; bottom 22% status bar (`HP`, `FAVOR`, reactive Spartan demigod portrait, `[Q][W][E][R]` keycap badges).
3. **Screen 3 — Olympian Boon Draft (`screen_03_boon_draft.png`):** Between stages, the arena blurs and 3 vertical Boon cards slide in. Press `[1]`, `[2]`, or `[3]` (fallback `[A/S/D]`) to claim immediately.
4. **Screen 4 — Gate Guardian Boss Fight (`screen_04_boss_and_shrine.png`):** Stage 5 of every Gate replaces the wave timer with a full-width crimson Boss Health Bar. Defeating the Guardian seals the Gate and unlocks the next.
5. **Screen 5 — Fallen / Underworld Shrine:** Displays Wave Reached, Enemies Banished, and Obols Earned. Press `[R]` for an instant (<200ms) retry or `[U]` to spend Obols on permanent stat upgrades.

## 5. Gate & Stage Structure (4 Gates × 5 Stages = 20 Stages)
Every Gate follows a 5-stage escalation (~4–5 minute run):
- **Stage 1 (Threshold, 40–45s):** Open arena, base swarm enemies.
- **Stage 2 (Pillared Hall, 50–55s):** Tighter pillar cover, introduces ranged/flanking enemies.
- **Stage 3 (The Trial, 55–60s):** Activates signature Gate environmental hazard + Elite spawns.
- **Stage 4 (Onslaught, 60–65s):** Peak crowd density, full enemy mix.
- **Stage 5 (Guardian Sanctum, Boss):** Gate Guardian boss + periodic minion spawns.
