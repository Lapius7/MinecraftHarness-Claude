---
name: minecraft-harness
description: Reference for MinecraftHarness building primitives, modifiers, region shifting, world inspection, and commands.
---

# MinecraftHarness Tool Reference

Local coordinates are integer `[right, up, forward]` offsets from the active anchor (or `anchorId` such as `"s1"` / `"last"`). Facing tokens: `front` (+forward, away from camera), `back` (-forward, toward camera), `right` (+right), `left` (-right). Smaller `f` is closer to the player (the front facade facing the player); larger `f` is farther away.

## `mc_build_batch`
Emit keys in order: `anchorId`, `coordMode` (`"local"` default), `label`, `sortMode` (`"bottom_up"` default), `ops`, `commands`, `finish` (`true` default), `reply`.
Any op supports modifiers:
- `mirrorX: true` (optional `mirrorCenter`: integer R) — mirrors across R and auto-flips left/right `facing` & door `hinge`
- `repeat: { axis:"r"|"u"|"f", count:N, step:S }` or `{ offset:[dr,du,df], count:N }`

Region shifting & copying:
- `move` / `shift` / `copy`: `{ type:"move", from:[r1,u1,f1], to:[r2,u2,f2], by:[dr,du,df], dy:-3, structureId:"s1"|"last", copy:false, includeAir:false, clearFill:"air" }` (`type:"copy"` defaults `copy:true`; omit `from`/`to` when using `structureId`)

Geometric & custom ops:
- `box`: `{ type:"box", from, to, material|palette }`
- `hollow_box`: `{ type:"hollow_box", from, to, thickness:1, floorMaterial, wallMaterial, roofMaterial }`
- `pillar`: `{ type:"pillar", at:[r,u,f], height, radius:1, material, capMaterial }`
- `cylinder`: `{ type:"cylinder", center, radius, height, hollow:true, axis:"y", material|palette }`
- `dome`: `{ type:"dome", center, radius, hollow:true, half:"upper", material }`
- `arch`: `{ type:"arch", from, to, height, thickness:1, material }`
- `stairs_run`: `{ type:"stairs_run", from, to, width:3, material, railMaterial, autoHeadroom:true }`
- `bridge`: `{ type:"bridge", from, to, width:3, sag:1.5, material, railMaterial }`
- `spiral`: `{ type:"spiral", center, radius, height, turns, width, material }`
- `ring`: `{ type:"ring", center, radius, innerRadius, thickness:1, axis:"y", material }`
- `scatter`: `{ type:"scatter", from, to, density:0.2, onlyAir:true, palette }`
- `blocks`: `{ type:"blocks", items:[{ pos, material }] }` or `{ type:"blocks", positions:[...], material }`
- `clear`: `{ type:"clear", from, to }`
- `pattern`: `{ type:"pattern", plane:"floor"|"front"|"side", at, rows:["..."], key:{"T":"spruce_planks"} }` (spaces and `.` skip; elevation rows read top-to-bottom)

Plugin auto-fixers (always active):
- Doors auto-emit their upper half & double-door hinges; beds auto-emit head half; 2-block tall flora auto-emit upper half; `stairs_run` auto-clears 3-block headroom; `water_cauldron` auto-fills `level=3`.

## Other Tools (all support optional `anchorId`)
- `mc_fx`: `effect`: `finish`, `shockwave`, `firework`, `title`, `time_night`, `time_sunset`, `time_day`, `lightning`, `illumination` (with `preset: "sparkle"|"firefly"|"magic"|"aurora"`, `from`, `to`), `clear_illumination`.
- `mc_exec_commands`: One-off 1.21.4 commands (`{{REL r u f}}` supported) + optional `observe:{ at, afterMs }`.
- `mc_place_command_chain`: Persistent command block chain (`mode`, `placement`, `commands`, `trigger`).
- `mc_function_pack`: Persistent datapack (`id`, `action:"deploy"|"remove"`, `functions:{ tick:[...], load:[...] }`).
- `mc_inspect_world`: Scan blocks/entities (`from`, `to`, `view:"front"|"side"|"top"`, `sampleCoords`).
- `mc_take_photo`: Capture Minecraft window screenshot (`angle:"current"|"front"|"orbit_left"|"orbit_right"|"top_overview"|"custom"`).
