# Metallum with Shader

Fork von [Metallum](https://github.com/kokodio/metallum) (Metal-Backend für Minecraft auf macOS) mit eingebautem Shaderpack-Loader. Das Ziel: MakeUp UltraFast mit Schatten soll auf einem MacBook Air M1 (8 GB) flüssig laufen.

Minecraft 26.3, Fabric Loader 0.19.5, Apple Silicon.

## Installation

1. `Metallum-with-Shader-<version>.jar` (aus den Releases oder `build/libs/`) in den `mods`-Ordner legen. Andere Metallum-Jars entfernen, weil die Mod-ID gleich ist.
2. `shaderpacks/MakeUp-UltraFast-Metallum-with-Shader.zip` in den `shaderpacks`-Ordner legen.
3. `config/minecraft-shader-loader.properties` in den `config`-Ordner legen. Die Datei wählt das Pack aus und enthält alle Schalter.

Bauen geht mit Java 25: `./gradlew build`. Das Jar landet in `build/libs/`.

## Messwerte (MacBook Air M1, 1144×643, Sichtweite 8)

| Stand | FPS |
|---|---|
| Vanilla | ~200 |
| MakeUp mit Schatten, alter Loader | 10–18 |
| Dieser Fork, renderScale 0.8 | ~75 beim Fliegen, ~55 beim Laufen |

## Änderungen gegenüber Metallum und dem Shader-Loader

- **Schattenkarten-Cache:** Opakes Terrain wird nur neu gerendert, wenn sich der Sonnenwinkel, das Kamera-Rasterfeld oder Chunks ändern oder wenn N Frames vergangen sind. Der Neuaufbau läuft verteilt über mehrere Frames in eine zweite Karte, danach werden die Karten getauscht.
- **Render-Skalierung:** Das Pack rendert in reduzierter Auflösung, der `final`-Pass skaliert hoch.
- **Kein Alpha-Test-`discard` auf opaken Pipelines,** damit die Hidden-Surface-Removal der GPU greift.
- **Regex-Cache in `PackPrograms.sequence`:** Vorher entstanden ~500 MB Müll pro Sekunde, mit GC-Pausen von 30–65 ms.
- **Echte GPU-Zeitstempel** für den Profiler.
- **Config-Brücke:** Alle `minecraftShaders.*`-, `minecraftScene.*`- und `metallum.*`-Keys aus der Properties-Datei werden zu System-Properties.
- **Tracked Hazards** statt globaler Fence-Kette im Metal-Backend.
- **Hardware-Schattenvergleich** (`depth-comparison-lequal`).
- Kompatibilitäts-Fixes für MakeUp: vec2-Custom-Uniforms, Material-IDs, shadowcolor-Formate, `gl_FogFragCoord`, NaN-Matrix im ersten Frame.

## Schalter (`config/minecraft-shader-loader.properties`, Neustart nötig)

| Key | Standard | Wirkung |
|---|---|---|
| `minecraftShaders.shadowCache` | true | Schatten-Cache |
| `minecraftShaders.shadowCacheAngle` | 0.25 | Sonnenwinkel-Schritt in Grad |
| `minecraftShaders.shadowCacheMaxAge` | 120 | erzwungener Neuaufbau nach N Frames |
| `minecraftShaders.shadowCacheSlices` | 8 | Neuaufbau auf N Frames verteilen |
| `minecraftShaders.renderScale` | 0.8 | interne Auflösung (1 = nativ) |
| `metallum.trackedHazards` | true | false = alte Fence-Kette |
| `minecraftShaders.hardwareShadowComparison` | true | false = emuliert |
| `minecraftShaders.keepOpaqueAlphaTest` | false | true = alter Alpha-Test |
| `minecraftShaders.profiler.enabled` | false | GPU-Messung nach `logs/shader-profile.jsonl` |

## Shaderpack

`shaderpacks/MakeUp-UltraFast-Metallum-with-Shader.zip` basiert auf **[MakeUp Ultra Fast](https://github.com/javiergcim/MakeUpUltraFast) 9.5g** von javiergcim und steht unter LGPL-3.0 (siehe `LICENSE` im Zip). Geändert wurden nur `shaders/lib/config.glsl` und `shaders/shaders.properties`, und zwar diese Einstellungen:

- Reflexionen auf Stufe 1
- AO aus
- AA: nur Denoise
- farbige Schatten, Bloom und volumetrisches Licht aus
- Wolken mit 7 Samples
- kurze Schattendistanz (75 Blöcke, Qualitätsstufe 1)
- `shadow.translucent=false`

## Lizenzen

- Metallum: MIT, © kokodio (siehe `LICENSE`)
- MakeUp Ultra Fast: LGPL-3.0, © javiergcim
- Shader-Loader (`dev.kausik.shaders`): stammt von Kausik. Die Lizenz ist hier nicht dokumentiert, deshalb ist dieses Repo privat.
