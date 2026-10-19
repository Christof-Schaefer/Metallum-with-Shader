# Metallum with Shader

A fork of [Metallum](https://github.com/kokodio/metallum), the Metal rendering backend for Minecraft on macOS. It adds a built-in shader pack loader based on [Kausik-Velaga/minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer). The goal is to run MakeUp UltraFast with shadows smoothly on a MacBook Air M1 (8 GB). Right now were are at about 60 fps.

Minecraft 26.3, Fabric Loader 0.19.5, Apple Silicon only.

> [!IMPORTANT]
> **This mod only works with the shader pack bundled in this repository**, `shaderpacks/MakeUp-UltraFast-Metallum-with-Shader.zip`. That pack is a modified version of MakeUp UltraFast 9.5g.
>
> The loader's compatibility fixes and performance work were built and tested against exactly this pack. Other shader packs, and other versions of MakeUp (including the unmodified 9.5g), are not supported. They may fail to load, render incorrectly or run slowly.

## Installation

1. Put `Metallum-with-Shader-<version>.jar` in your `mods` folder. You can get it from the [Releases](../../releases) or build it yourself. Remove any other Metallum jar first, because they share the mod id `metallum`.
2. Put `MakeUp-UltraFast-Metallum-with-Shader.zip` in your `shaderpacks` folder.
3. Put `minecraft-shader-loader.properties` in your `config` folder. This file selects the pack and holds all switches. The pack is not chosen in a menu.

To build, use Java 25 and run `./gradlew build`. The jar ends up in `build/libs/`.

## Results

Measured on a MacBook Air M1 at 1144×643 with render distance 8:

| Setup | FPS |
|---|---|
| Vanilla | ~200 |
| MakeUp with shadows, original loader | 10–18 |
| This fork, `renderScale=0.8` | ~75 flying, ~55 walking |

Walking is slower than flying because the shadow cache rebuilds more often while walking. It is triggered by the camera crossing grid cells and by chunk updates.

## Changes compared to Metallum and the original loader

- **Shadow map cache.** Opaque terrain is only re-rendered into the shadow map when one of these happens: the sun angle changes, the camera enters a new grid cell, chunks change, or a maximum age is reached. The rebuild goes into a second map, spread over several frames, and the maps are then swapped.
- **Render scale.** The pack renders at a reduced resolution, and the `final` pass upscales the result.
- **No alpha-test `discard` on opaque pipelines.** This lets the GPU's hidden-surface removal work again.
- **Regex cache in `PackPrograms.sequence`.** The loader used to compile regexes every frame. That produced about 500 MB of garbage per second and GC pauses of 30–65 ms.
- **Real GPU timestamps** for the built-in profiler.
- **Config bridge.** Every `minecraftShaders.*`, `minecraftScene.*` and `metallum.*` key in the properties file becomes a system property.
- **Tracked hazards** in the Metal backend replace the global fence chain.
- **Hardware shadow comparison** using `depth-comparison-lequal`.
- **MakeUp compatibility fixes:** vec2 custom uniforms, material IDs, shadowcolor formats, `gl_FogFragCoord`, and a NaN matrix on the first frame.

## Switches

All switches live in `config/minecraft-shader-loader.properties`. A restart is required after changing them.

| Key | Default | Effect |
|---|---|---|
| `minecraftShaders.shadowCache` | true | Shadow map cache |
| `minecraftShaders.shadowCacheAngle` | 0.25 | Sun angle step in degrees that triggers a rebuild |
| `minecraftShaders.shadowCacheMaxAge` | 120 | Forced rebuild after N frames |
| `minecraftShaders.shadowCacheSlices` | 8 | Spread a rebuild over N frames |
| `minecraftShaders.renderScale` | 0.8 | Internal resolution (1 = native) |
| `metallum.trackedHazards` | true | false = old fence chain |
| `minecraftShaders.hardwareShadowComparison` | true | false = emulated comparison |
| `minecraftShaders.keepOpaqueAlphaTest` | false | true = old alpha test |
| `minecraftShaders.profiler.enabled` | false | Per-pass GPU timing written to `logs/shader-profile.jsonl` |

## Shader pack

`shaderpacks/MakeUp-UltraFast-Metallum-with-Shader.zip` is based on [MakeUp Ultra Fast](https://github.com/javiergcim/MakeUpUltraFast) 9.5g by javiergcim and is licensed under LGPL-3.0; see `LICENSE` inside the zip.

Only `shaders/lib/config.glsl` and `shaders/shaders.properties` were changed:

- Reflection quality set to 1
- Ambient occlusion off
- Antialiasing set to denoise only
- Colored shadows, bloom and volumetric light off
- Volumetric clouds use 7 samples
- Short shadow distance (75 blocks, quality level 1)
- `shadow.translucent=false`

## Licenses

- Metallum: MIT, © kokodio (see `LICENSE`)
- Shader loader and scene API (`dev.kausik.*`): MIT, © Minecraft Metal Renderer contributors, from [Kausik-Velaga/minecraft-metal-renderer](https://github.com/Kausik-Velaga/minecraft-metal-renderer) (see `LICENSES/MIT-minecraft-metal-renderer.txt`)
- MakeUp Ultra Fast: LGPL-3.0, © javiergcim

This is an unofficial project. It is not approved by or associated with Mojang or Microsoft.
