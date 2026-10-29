# Platt — Paper 1.21.11 (0.1.0-alpha source project)

**Status: uncompiled prototype source + datapack + resourcepack.** The Paper API dependency could not be fetched in the authoring environment, so **no compiled or play-tested JAR is included**. Build on a machine with Java 21 and Gradle plus internet access (`gradle build`), then test on a dedicated Paper 1.21.11 staging server. The code is provided for modification, not represented as production-ready.

## Contents

- `src/main/java/dev/platt/PlattPlugin.java` — charging, skill casts, commands, persistence, smithing and ally protection.
- `src/main/resources/config.yml` — commented values for ALL 13 combos, each ability, cooldown, damage, durations and server behavior.
- `Platt_Datapack_1.21.11.zip` — native `platt:platt` trim pattern + breeze/echo custom trim materials.
- `Platt_ResourcePack_1.21.11.zip` — Platt smithing-template icon and 26 on-armor UV sprites (humanoid & leggings × 13 material palettes), plus breeze/echo inventory trim sprites.
- `artwork/trim-preview.png` — preview of original pixel-art UV trim overlays, NOT a screenshot of Minecraft.
- `generate_assets.py` — regenerate ZIPs from editable pixel generation source.

## Build and installation

1. Install **Java 21** and **Gradle 8.8+**. From this folder: `gradle build`. If the dependency resolves and compilation succeeds, the jar appears in `build/libs/`.
2. Place **`Platt_Datapack_1.21.11.zip`** into **`world/datapacks/` before starting the server**. For multiple worlds, registry packs should be loaded with the server's primary world at startup. This is essential: the plugin cannot register custom `trim_pattern` or `trim_material` registry values after world load.
3. Install the built plugin JAR into `plugins/`, then **restart the server**. Verify the console has no missing-datapack warning. Do not rely on `/reload` to add registry data.
4. Put **`Platt_ResourcePack_1.21.11.zip`** in the Minecraft 1.21.11 client's resource-packs folder, OR host the ZIP and set Paper `server.properties` `resource-pack` URL and SHA-1. Every player must load this pack to see the custom trim on equipped armor and template icon.
5. The plugin auto-creates `plugins/Platt/config.yml` and saves per-player states & trust to `players.yml`.
6. In-game: craft a Platt Template, combine it + ANY helmet/chestplate/leggings/boots + a supported material at a Smithing Table.

### Recipe (all materials share it)

```
IRON INGOT    REDSTONE DUST   IRON INGOT
REDSTONE DUST DIAMOND         REDSTONE DUST
IRON INGOT    REDSTONE DUST   IRON INGOT
```

This crafts one Platt Template. Template is consumed by smithing. Re-trimming an item can be permitted or disabled in config. Base armor may be leather, chainmail, copper, iron, gold, diamond, netherite, or other vanilla player armor with an `ArmorMeta` and helmet/chestplate/leggings/boots suffix. It does NOT trim swords, tools or unrelated equipment: sword/tool powers are granted by equipped armor.

## How to charge / use

Each material has a specific **consecutive combat combo**. Actions count toward a charge within the material's time window. Once achieved, the power stays **READY indefinitely**, including if you log out or change armor. Only a successful skill cast consumes that ready charge and starts the ability cooldown. You may build the next charge while the cooldown is running. A charge does not expire unless config resets it on death.

- Sneak + **right-click**: main ability (all 13 materials).
- Sneak + **left-click air/block**: secondary skill for Breeze / Echo.
- `/platt use 1` or `/platt use 2`: reliable command backup; melee left-click on entities is not captured by the second hotkey.
- `/platt status`: selected material, combo progress, ready state, cooldown.
- `/platt select <material>`: choose among multiple materials on your equipped armor. Default priority is chestplate, leggings, helmet, boots.
- Combat hits must land and satisfy optional `charge.count-pve-hits` setting; settings are editable in `config.yml`.

| Material | Charging combat style | Requirement (default) | Main ability | Secondary (special only) |
|---|---|---:|---|---|
| Lapis Lazuli | Any melee hits | 4 | Enchantment Surge | — |
| Iron | Block incoming hits with a shield | 3 | Iron Guard | — |
| Redstone | Sprint-melee hits | 3 | Piston Pulse | — |
| Copper | Receive damage from enemies | 3 | Lightning Rod | — |
| Gold | Receive damage from enemies | 3 | Golden Heart (auto-triggers on low health if ready) | — |
| Emerald | Melee hits | 5 | Temporary Iron Golem guardian | — |
| Diamond | Airborne critical hits | 3 | Diamond Edge | — |
| Netherite | Receive damage from enemies | 4 | Unbreakable | — |
| Quartz | Arrow / trident projectile hits | 2 | Observer | — |
| Amethyst | Airborne critical hits | 2 | Crystal Growth (non-griefing version) | — |
| Resin | Hit enemy from behind | 2 | Temporary Creaking | — |
| **Breeze Rod** | Falling / aerial melee hits | **2** | Wind Leap | Sword Crushing Blow |
| **Echo Shard** | Sneaking melee hits | **3** | Warden Sonic Boom | Resonance Strike |

**Note:** Default Diamond/Amethyst critical-hit detection approximates a fall-critical (not every vanilla critical-hit precondition), so full PvP testing is required before release.

### Command permissions

| Command | Use | Permission |
|---|---|---|
| `/platt status` | Charge/ready/cooldown | platt.use |
| `/platt select <material>` | Choose armor power | platt.use |
| `/platt use [1|2]` | Trigger power | platt.use |
| `/platt template [online player]` | Admin give template | platt.admin |
| `/platt reload` | Re-read config | platt.admin |
| `/trust add <player>` | Add ally, no friendly fire | everyone |
| `/trust remove <player>` | Remove ally | everyone |
| `/trust list` | List trusted players | everyone |
| `/cooldown reset [online player] [material|all]` | Clear cooldowns; optionally grant charged-ready | platt.admin |

If `cooldown reset` is used in console, specify online player name. Player state, selections, and trust are saved on clean shutdown or after trust/admin commands. Combat charge updates are saved at shutdown, not every hit.

## Important alpha limitations

- **Not compiled or verified against a running Paper 1.21.11 server yet.** Check console / logs before installing into an existing SMP. Specifically test preview, smithing shift-click, inventory/armor rendering, custom registry loading, status and power casts.
- The **custom on-armor design is actual armor UV pixel art**, but is not a Blockbench 3D model; Minecraft trim geometry is an overlay on vanilla armor. Custom sprites are loaded from the resource pack. A Java Edition client resource pack is necessary.
- Some effects are *safer approximations* of the agreed concepts: Redstone pushes and can briefly power nearby switches if explicitly opted in (`power-redstone-components: true`); Amethyst uses particles + slow instead of replacing protected blocks; Resin summons a temporary Creaking, not a damageable, linked Creaking Heart structure; Emerald uses a short Hero-of-the-Village trade-discount window once per day (rather than a strict single-trade-only discount). Copper chains an additional small shock to nearby submerged players. Lapis augments existing enchanted combat/mining rather than literally increasing enchantment NBT levels.
- **Trust** prevents attacks by trusted players (and vice versa) and removes trusted people from direct AoE targeting. It does NOT integrate with faction/claim plugins automatically; Creaking/Golem AI can still require additional ally filters and should be tested in PvP.
- Server admins should check interactions with anticheat, protection/claim, vanish, PvP flags and custom item plugins. Resource pack merging can require combining atlas JSON when other packs also edit armor trims.
- This is experimental alpha-quality source, not claimed to be a finished server-certified plugin.

## Version references

- Minecraft 1.21.11 uses data pack **94.1** and resource pack **75.0**.
- Paper `paper-api:1.21.11-R0.1-SNAPSHOT`, Java 21, native `ArmorMeta`/`ArmorTrim` registries, custom pattern via datapack.
- Asset atlas paths are `assets/minecraft/atlases/armor_trims.json` and `assets/minecraft/atlases/items.json` with `minecraft:single` sprites. All custom armor-material combinations use explicit colored sprites to avoid gray-base palette matching issues.

### Build automatically on GitHub

Push this project to a GitHub repository, open **Actions → Build Platt (Paper 1.21.11) → Run workflow**, then retrieve the build artifact if Gradle compilation succeeds. This repository includes `.github/workflows/build.yml` for that purpose. Any compilation errors should be resolved against the exact Paper 1.21.11 API before server deployment.

## Platt naming

The Paper plugin is named **Platt** and its main command is **`/platt`**. The previous `/awakening` command is a backwards-compatible alias. The plugin data folder is `plugins/Platt/`. Resource/data namespaces are `platt`, and the smithing template is the **Platt Smithing Template**.

The download does **not** contain a compiled `.jar`. It requires a Paper API dependency to compile and a live Paper 1.21.11 server to test. If building through GitHub, Actions > Build Platt (Paper 1.21.11) > Run workflow will produce a jar build artifact *if the compilation succeeds*.
