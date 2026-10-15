# SubmarineFloodPlugin

Paper 1.21.11 plugin that simulates flooding of a submarine (or any watertight compartment) with real water blocks.
Java 21, Gradle (Kotlin DSL), Brigadier commands.

## How it works

1. You create a named flood (`/flood create sub1`) and add one or more **breach** points inside the hull.
2. On `start` the plugin runs a time-sliced **flood fill** from the breaches through air, floodable (waterloggable) partial
   blocks (stairs, slabs, trapdoors, panes, fences, ...) and open doors. Walls stop the fill, so sealed compartments
   without a breach stay dry.
3. Found blocks are sorted **bottom to top** (inside a layer: nearest to the breach first) and filled at the configured
   **rate** (blocks/second): air becomes a water source, partial blocks get `waterlogged=true`.
4. `pump` drains the water **top to bottom**; `reset` drains everything at max speed and clears the plan.
5. Water of the compartment is not allowed to flow out of it (`BlockFromToEvent` is cancelled for flow leaving the region).
6. Progress (plan, flooded blocks, breaches, rate, state) is saved and restored after a restart.

## Commands (permission `flood.admin`)

| Command | Description |
|---|---|
| `/flood create <name> [world]` | create a flood |
| `/flood delete <name> [force]` | delete (refuses while water remains, unless `force`) |
| `/flood list`, `/flood status [name]` | overview / details |
| `/flood breach add\|remove <name> [x y z]` | add/remove a breach (default: your position) |
| `/flood breach list <name>` | list breaches |
| `/flood start <name>` | scan the compartment (if needed) and start flooding / resume |
| `/flood pump <name>` | start draining top to bottom |
| `/flood pause <name>` / `stop` | pause |
| `/flood rate <name> <blocks/s>` | change intensity (live) |
| `/flood reset <name>` | fast full drain, then clear the plan |
| `/flood reload` | reload config.yml |

Adding/removing a breach while flooding triggers a re-scan and flooding continues.

## Configuration

See `config.yml` (language `auto|ru|en`, default/max rate, region size limit, scan budget, autosave, resume on start).

## Notes and limitations

- The hull must be watertight. If the fill finds more than `max-region-blocks` blocks the scan is aborted.
- A closed door / closed trapdoor / any non-waterloggable block is a wall; open doors and gates let water pass.
- Unloaded chunks are walls for the scan, and flooding/pumping waits while the next block's chunk is not loaded.
- Flood from a real hole in the hull: vanilla ocean water will also enter through it and refill after pumping.
  Use a sealed hull and simulated breach points.
- Blocks changed by players during flooding (new walls) are skipped.

## Build

```
./gradlew build      # jar in build/libs
./gradlew runServer  # test server (Paper 1.21.11)
```
