package org.sableos.games

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * How the foes of Tartarus behave. Every foe runs the same small cycle: Spawn, Move, Windup (the warning the player
 * can read: a flash, a red circle or line on the floor), Strike, Recover. The handoff's attack_type picks the moves.
 * The four Guardians chain several of them (see [bossMoves]).
 */

private const val ORB_SPEED = 7.5f
private const val FIRE_SPEED = 11f
private const val ORB_R = 0.5f

/** Moves a foe one step toward (ux, uy), keeping it out of pillars and inside the wall. */
private fun TbRun.walk(
    e: TbEnemy,
    ux: Float,
    uy: Float,
    speed: Float,
    dt: Float,
): Boolean {
    val nx = e.x + ux * speed * dt
    val ny = e.y + uy * speed * dt
    if (e.def.flies) {
        e.x = nx
        e.y = ny
        return false
    }
    val v = pushOut(nx, ny, e.def.radius)
    val bumped = hypot(v.first - nx, v.second - ny) > 0.02f
    e.x = v.first
    e.y = v.second
    return bumped
}

private fun TbRun.shootAt(
    e: TbEnemy,
    angle: Float,
    speed: Float,
    color: Long,
    dmg: Float = e.def.damage.toFloat(),
) {
    projs += TbProj(e.x + cos(angle), e.y + sin(angle), cos(angle), sin(angle), speed, dmg, ORB_R, false, color, 1, 4f)
}

private fun TbRun.fan(
    e: TbEnemy,
    center: Float,
    n: Int,
    spreadRad: Float,
    speed: Float,
    color: Long,
) {
    for (i in 0 until n) shootAt(e, center + (if (n == 1) 0f else (i / (n - 1f) - 0.5f) * spreadRad), speed, color)
}

private fun TbRun.lineTele(
    e: TbEnemy,
    angle: Float,
    len: Float,
    width: Float,
    t: Float,
    dmg: Int,
    slows: Boolean = false,
) {
    teles += TbTele(true, e.x, e.y, e.x + cos(angle) * len, e.y + sin(angle) * len, width, t, t, dmg, slows)
}

private fun TbRun.circleTele(
    x: Float,
    y: Float,
    r: Float,
    t: Float,
    dmg: Int,
    leaper: TbEnemy? = null,
) {
    teles += TbTele(false, x, y, x, y, r, t, t, dmg, leaper = leaper)
}

internal fun TbRun.updateFoe(
    e: TbEnemy,
    dt: Float,
) {
    e.age += dt
    e.flash = max(0f, e.flash - dt)
    if (e.state == TbEState.Dying) {
        e.fall += dt / TbRun.DEATH_TIME
        return
    }
    if (e.burn > 0f) {
        e.burn -= dt
        e.hp -= e.burnDps * dt
        if (e.hp <= 0f) {
            kill(e)
            return
        }
    }
    e.marked -= dt
    e.cooldown -= dt
    if (e.vx != 0f || e.vy != 0f) {
        val v = pushOut(e.x + e.vx * dt, e.y + e.vy * dt, e.def.radius)
        if (!e.def.flies) {
            e.x = v.first
            e.y = v.second
        } else {
            e.x += e.vx * dt
            e.y += e.vy * dt
        }
        val damp = max(0f, 1f - 7f * dt)
        e.vx *= damp
        e.vy *= damp
        if (hypot(e.vx, e.vy) < 0.15f) {
            e.vx = 0f
            e.vy = 0f
        }
    }
    val dx = px - e.x
    val dy = py - e.y
    val d = max(hypot(dx, dy), 0.001f)
    val ux = dx / d
    val uy = dy / d
    when (e.state) {
        TbEState.Spawn -> {
            e.timer -= dt
            if (e.timer <= 0f) e.state = TbEState.Move
        }

        TbEState.Stun -> {
            e.timer -= dt
            if (e.timer <= 0f) e.state = TbEState.Move
        }

        else -> {
            if (e.boss) bossMoves(e, dt, d, ux, uy) else foeMoves(e, dt, d, ux, uy)
        }
    }
    separate(e)
}

/** Foes shoulder each other apart so a crowd spreads around the player instead of stacking up. */
private fun TbRun.separate(e: TbEnemy) {
    for (o in enemies) {
        if (o === e || !o.alive || o.def.flies != e.def.flies) continue
        val dx = e.x - o.x
        val dy = e.y - o.y
        val d = hypot(dx, dy)
        val min = (e.def.radius + o.def.radius) * 0.9f
        if (d < min && d > 1e-3f) {
            val push = (min - d) * 0.25f
            e.x += dx / d * push
            e.y += dy / d * push
        }
    }
}

/** Common to every foe: after [t] seconds in a state, run [then]. Returns true if the state ended. */
private fun TbRun.tick(
    e: TbEnemy,
    dt: Float,
    then: () -> Unit,
): Boolean {
    e.timer -= dt
    if (e.timer > 0f) return false
    then()
    return true
}

private fun TbRun.recover(
    e: TbEnemy,
    t: Float,
    cooldown: Float,
) {
    e.state = TbEState.Recover
    e.timer = t
    e.cooldown = cooldown
}

private fun TbRun.windup(
    e: TbEnemy,
    t: Float,
) {
    e.state = TbEState.Windup
    e.timer = t
}

private fun TbRun.foeMoves(
    e: TbEnemy,
    dt: Float,
    d: Float,
    ux: Float,
    uy: Float,
) {
    val def = e.def
    val speed = def.speed
    if (e.state == TbEState.Recover) {
        tick(e, dt) { e.state = TbEState.Move }
        return
    }
    when (def.attack) {
        "melee" -> {
            when (e.state) {
                TbEState.Move -> {
                    walk(e, ux, uy, speed, dt)
                    if (d < def.range * 0.9f && e.cooldown <= 0f) windup(e, 0.45f)
                }

                else -> {
                    tick(e, dt) {
                        if (d < def.range * 1.5f) hurt(def.damage.toFloat(), e.x, e.y)
                        recover(e, 0.5f, 0.4f)
                    }
                }
            }
        }

        "melee_lunge" -> {
            lunge(e, dt, d, ux, uy)
        }

        "melee_explode" -> {
            when (e.state) {
                TbEState.Move -> {
                    walk(e, ux, uy, speed, dt)
                    if (d < def.range) windup(e, 0.5f)
                }

                else -> {
                    tick(e, dt) {
                        if (d < 3.3f) hurt(def.damage.toFloat(), e.x, e.y)
                        fx += TbFx(TbFxKind.Burst, e.x, e.y, 0f, 0f, 0xFFFF7B00, 0.5f, n = 2)
                        e.pays = false
                        e.state = TbEState.Dying
                        e.fall = 0f
                    }
                }
            }
        }

        "projectile_slow", "triple_orb" -> {
            kite(e, dt, d, ux, uy, near = 8f, far = 12.5f)
            if (e.state == TbEState.Move && e.cooldown <= 0f && d < def.range + 2f) windup(e, 0.5f)
            if (e.state == TbEState.Windup) {
                tick(e, dt) {
                    val a = atan2(py - e.y, px - e.x)
                    if (def.attack == "triple_orb") fan(e, a, 3, 0.5f, ORB_SPEED + 1f, 0xFFC77DFF) else shootAt(e, a, ORB_SPEED, 0xFF7FE8FF)
                    e.state = TbEState.Move
                    e.cooldown = if (def.attack == "triple_orb") 2.8f else 2.4f
                }
            }
        }

        "swoop_fireball" -> {
            swoop(e, dt, d, ux, uy)
        }

        "boulder_lob" -> {
            when (e.state) {
                TbEState.Move -> {
                    if (d > 11f) walk(e, ux, uy, speed, dt) else if (d < 6f) walk(e, -ux, -uy, speed * 0.6f, dt)
                    if (e.cooldown <= 0f && d < def.range) {
                        windup(e, 0.7f)
                        circleTele(px, py, 3.2f, 1.15f, def.damage)
                    }
                }

                else -> {
                    tick(e, dt) { recover(e, 1.0f, 3.2f) }
                }
            }
        }

        "telegraphed_charge" -> {
            charge(e, dt, d, ux, uy, speed = 17f, time = 1.1f, wind = 0.8f)
        }

        "petrify_beam" -> {
            when (e.state) {
                TbEState.Move -> {
                    kite(e, dt, d, ux, uy, near = 8f, far = 13f)
                    if (e.cooldown <= 0f && d < def.range) {
                        windup(e, 1.0f)
                        lineTele(e, atan2(py - e.y, px - e.x), 32f, 0.8f, 1.0f, def.damage, slows = true)
                    }
                }

                else -> {
                    tick(e, dt) { recover(e, 0.8f, 3.4f) }
                }
            }
        }

        "ground_slam" -> {
            when (e.state) {
                TbEState.Move -> {
                    walk(e, ux, uy, speed, dt)
                    if (d < def.range && e.cooldown <= 0f) {
                        windup(e, 0.9f)
                        circleTele(e.x, e.y, 5.2f, 0.9f, def.damage)
                    }
                }

                else -> {
                    tick(e, dt) { recover(e, 1.1f, 1.4f) }
                }
            }
        }

        else -> {
            walk(e, ux, uy, speed, dt)
        }
    }
}

/** Ranged foes keep their distance: close in when far, back off when near, circle in between. */
private fun TbRun.kite(
    e: TbEnemy,
    dt: Float,
    d: Float,
    ux: Float,
    uy: Float,
    near: Float,
    far: Float,
) {
    if (e.state != TbEState.Move) return
    val s = e.def.speed
    when {
        d > far -> walk(e, ux, uy, s, dt)
        d < near -> walk(e, -ux, -uy, s * 0.9f, dt)
        else -> walk(e, -uy * e.orbitDir, ux * e.orbitDir, s * 0.7f, dt)
    }
}

private fun TbRun.lunge(
    e: TbEnemy,
    dt: Float,
    d: Float,
    ux: Float,
    uy: Float,
) {
    when (e.state) {
        TbEState.Move -> {
            walk(e, ux, uy, e.def.speed, dt)
            if (d in 2.5f..7.5f && e.cooldown <= 0f) {
                windup(e, 0.55f)
                e.dirX = ux
                e.dirY = uy
                lineTele(e, atan2(uy, ux), 7f, 0.7f, 0.55f, 0)
            }
        }

        TbEState.Windup -> {
            tick(e, dt) {
                e.state = TbEState.Strike
                e.timer = 0.4f
                e.hit = false
            }
        }

        else -> {
            walk(e, e.dirX, e.dirY, 20f, dt)
            if (!e.hit && d < e.def.range * 0.9f + 0.6f) {
                e.hit = true
                hurt(e.def.damage.toFloat(), e.x, e.y)
            }
            tick(e, dt) { recover(e, 0.9f, 2.2f) }
        }
    }
}

private fun TbRun.swoop(
    e: TbEnemy,
    dt: Float,
    d: Float,
    ux: Float,
    uy: Float,
) {
    when (e.state) {
        TbEState.Move -> {
            e.orbit += e.orbitDir * (e.def.speed / 9f) * dt
            val tx = px + cos(e.orbit) * 9f
            val ty = py + sin(e.orbit) * 9f
            val dx = tx - e.x
            val dy = ty - e.y
            val len = max(hypot(dx, dy), 0.001f)
            walk(e, dx / len, dy / len, min(e.def.speed, len * 3f), dt)
            if (e.cooldown <= 0f && d < e.def.range + 4f) windup(e, 0.5f)
        }

        TbEState.Windup -> {
            tick(e, dt) {
                if (e.step % 3 == 2) {
                    e.dirX = ux
                    e.dirY = uy
                    e.state = TbEState.Strike
                    e.timer = 0.9f
                    e.hit = false
                } else {
                    shootAt(e, atan2(uy, ux), FIRE_SPEED, 0xFFFF9A3C)
                    e.state = TbEState.Move
                    e.cooldown = 2.4f
                }
                e.step++
            }
        }

        else -> {
            walk(e, e.dirX, e.dirY, 16f, dt)
            if (!e.hit && d < 1.4f) {
                e.hit = true
                hurt(e.def.damage.toFloat(), e.x, e.y)
            }
            tick(e, dt) {
                e.state = TbEState.Move
                e.cooldown = 3f
            }
        }
    }
}

private fun TbRun.charge(
    e: TbEnemy,
    dt: Float,
    d: Float,
    ux: Float,
    uy: Float,
    speed: Float,
    time: Float,
    wind: Float,
) {
    when (e.state) {
        TbEState.Move -> {
            walk(e, ux, uy, e.def.speed * (if (e.boss) 0.7f else 1f), dt)
            if (d in 3.5f..14f && e.cooldown <= 0f) {
                windup(e, wind)
                e.dirX = ux
                e.dirY = uy
                lineTele(e, atan2(uy, ux), 22f, e.def.radius * 0.55f, wind, 0)
            }
        }

        TbEState.Windup -> {
            tick(e, dt) {
                e.state = TbEState.Strike
                e.timer = time
                e.hit = false
            }
        }

        else -> {
            val bumped = walk(e, e.dirX, e.dirY, speed, dt)
            if (!e.hit && d < e.def.radius + 1.0f) {
                e.hit = true
                hurt(e.def.damage.toFloat(), e.x, e.y)
            }
            if (bumped) {
                e.state = TbEState.Stun
                e.timer = if (e.boss) 1.2f else 1.4f
                e.cooldown = 1.6f
                shake = max(shake, 0.6f)
                fx += TbFx(TbFxKind.Burst, e.x, e.y, 0f, 0f, 0xFFD9C7A0, 0.5f)
            } else {
                tick(e, dt) { recover(e, 0.7f, 1.8f) }
            }
        }
    }
}

// ---- the Guardians ------------------------------------------------------------------------------------------------

private fun TbRun.bossMoves(
    e: TbEnemy,
    dt: Float,
    d: Float,
    ux: Float,
    uy: Float,
) {
    if (!e.enraged && e.hp < e.maxHp * 0.5f) {
        e.enraged = true
        say("${e.def.name} is enraged", good = false)
        shake = 1f
    }
    val rage = if (e.enraged) 1.3f else 1f
    if (e.state == TbEState.Recover) {
        tick(e, dt) { e.state = TbEState.Move }
        return
    }
    when (e.def.id) {
        "asterion_the_bull" -> {
            if (e.step == -1) {
                if (e.state == TbEState.Windup) {
                    tick(e, dt) {
                        e.step = 0
                        recover(e, 0.6f, 0.7f)
                    }
                } else {
                    e.step = 0
                }
            } else {
                charge(e, dt, d, ux, uy, speed = 22f * rage, time = 1.3f, wind = 0.6f / rage)
                if (e.state == TbEState.Move && e.cooldown <= 0f && d < 3.5f) {
                    windup(e, 0.8f)
                    e.step = -1
                    circleTele(e.x, e.y, 4.5f, 0.8f, e.def.damage)
                }
            }
        }

        else -> {
            when (e.state) {
                TbEState.Move -> {
                    val want = if (e.def.id == "cerberus_hound") 8f else 10f
                    if (d > want) walk(e, ux, uy, e.def.speed * 0.6f * rage, dt) else if (d < want - 3f) walk(e, -ux, -uy, e.def.speed * 0.4f, dt)
                    if (e.cooldown <= 0f) startPattern(e, ux, uy)
                }

                TbEState.Windup -> {
                    tick(e, dt) {
                        e.state = TbEState.Strike
                        e.timer = patternTime(e)
                    }
                }

                else -> {
                    strikePattern(e, dt, ux, uy)
                    tick(e, dt) { recover(e, 0.7f, 1.3f / rage) }
                }
            }
        }
    }
}

private fun patternTime(e: TbEnemy) = if (e.def.id == "cerberus_hound" && (e.step - 1) % 2 == 0) 0.9f else 0.05f

private fun TbRun.startPattern(
    e: TbEnemy,
    ux: Float,
    uy: Float,
) {
    val a = atan2(uy, ux)
    val which = e.step % 2
    when (e.def.id) {
        "cerberus_hound" -> {
            if (which == 0) {
                windup(e, 0.8f)
                e.volley = 0f
            } else {
                windup(e, 1.0f)
                circleTele(px, py, 4.2f, 1.0f, e.def.damage, leaper = e)
            }
        }

        "hephaestus_automaton" -> {
            if (which == 0) {
                windup(e, 0.9f)
                circleTele(e.x, e.y, 6.5f, 0.9f, e.def.damage)
            } else {
                windup(e, 1.3f)
                lineTele(e, a + 0.3f, 32f, 1.0f, 1.3f, e.def.damage)
                lineTele(e, a - 0.3f, 32f, 1.0f, 1.3f, e.def.damage)
            }
        }

        else -> {
            if (which == 0) {
                windup(e, 0.8f)
                zones += TbZone(TbZoneKind.Rift, px, py, 4.5f, 0f, life = 5f)
                circleTele(px, py, 4.5f, 1.2f, e.def.damage / 2)
            } else {
                windup(e, 0.7f)
            }
        }
    }
    e.volley = 0f
    e.step++
    e.dirX = ux
    e.dirY = uy
}

/** What a Guardian does while its Strike lasts: Cerberus breathes fire in three volleys, Kronos sweeps a scythe wave. */
private fun TbRun.strikePattern(
    e: TbEnemy,
    dt: Float,
    ux: Float,
    uy: Float,
) {
    val a = atan2(uy, ux)
    val last = (e.step - 1) % 2
    when (e.def.id) {
        "cerberus_hound" -> {
            if (last == 0) {
                e.volley -= dt
                if (e.volley <= 0f) {
                    e.volley = 0.3f
                    fan(e, a, 5, 0.9f, FIRE_SPEED, 0xFFFF7B00)
                }
            }
        }

        "kronos_echo" -> {
            if (last == 1 && e.volley == 0f) {
                e.volley = 1f
                fan(e, a, 9, 1.5f, 9f, 0xFFC77DFF)
            }
        }
    }
}
