# Program icons

Hatch's own app icon and mascot are documented separately in
[`assets/hatch/README.md`](assets/hatch/README.md).

The logos on program tiles (`ProgramTile`, `ProgramGlyph`; the table is `ProgramIcons.kt`). They follow
the "Full catalogue" grid in the Paper design, section 9.

## Simple Icons (CC0 1.0)

One-path 24-unit marks from [Simple Icons](https://simpleicons.org) v15
(`https://cdn.jsdelivr.net/npm/simple-icons@15/icons/<slug>.svg`), converted to vector drawables
named `logo_<slug>`, filled white or black on the tile colour Paper uses (usually the brand
colour Simple Icons lists). Simple Icons releases its SVGs under CC0; the marks remain their owners' trademarks.

98 programs (program, slug): `android` (android), `apple-container` (apple), `bash` (gnubash), `bat` (bat), `bazel` (bazel), `boundary` (hashicorp), `brew` (homebrew), `browser` (googlechrome), `buildkite` (buildkite), `bun` (bun), `butler` (itchdotio), `caddy` (caddy), `clang` (llvm), `claude` (claude), `cloudflare` (cloudflare), `coder` (coder), `codex` (openai), `consul` (consul), `copilot` (githubcopilot), `cursor` (cursor), `dart` (dart), `datadog` (datadog), `docker` (docker), `dotnet` (dotnet), `duckdb` (duckdb), `elixir` (elixir), `emacs` (gnuemacs), `expo` (expo), `fish` (fishshell), `flutter` (flutter), `gemini` (googlegemini), `git` (git), `github` (github), `gitlab` (gitlab), `gnu` (gnu), `go` (go), `gradle` (gradle), `grok` (x), `heroku` (heroku), `htop` (htop), `hugging-face` (huggingface), `hx` (helix), `julia` (julia), `junie` (jetbrains), `jupyter` (jupyter), `k9s` (kubernetes), `kube` (kubernetes), `meta` (meta), `micro` (microeditor), `mistral` (mistralai), `modal` (modal), `moon` (moonrepo), `nano` (gnu), `neovim` (neovim), `nextflow` (nextflow), `nix` (nixos), `node` (nodedotjs), `nomad` (nomad), `nushell` (nushell), `ollama` (ollama), `packer` (packer), `php` (php), `pnpm` (pnpm), `podman` (podman), `postgres` (postgresql), `proto` (moonrepo), `proton-drive` (protondrive), `proton-pass` (proton), `python` (python), `railway` (railway), `rclone` (rclone), `redis` (redis), `render` (render), `ruby` (ruby), `rust` (rust), `sail` (laravel), `sanity` (sanity), `shopify` (shopify), `snowflake` (snowflake), `sqlite` (sqlite), `stripe` (stripe), `swift` (swift), `tailscale` (tailscale), `task` (task), `terraform` (terraform), `turborepo` (turborepo), `unreal-engine` (unrealengine), `uv` (uv), `vagrant` (vagrant), `vault` (vault), `vercel` (vercel), `vim` (vim), `vite` (vite), `vite-plus` (vite), `waypoint` (hashicorp), `xcode` (xcode), `zig` (zig), `zsh` (zsh).

`fish` uses Paper's charcoal tile, `#1C2024`, with the white Fish Shell mark.

`vault` draws its mark black rather than Paper's white, which is unreadable on its yellow tile.

## Project favicons and avatars

The projects' own marks, used only to identify the program in a private build (spec R14). Vector
ones were converted from the project's SVG favicon, dropping its background, which the tile colour
replaces. Bitmaps are in `drawable-nodpi` and cannot be tinted, so `ProgramGlyph` shows initials for them.

| Program | Source | Kept as |
|---|---|---|
| `amp` | https://ampcode.com/favicon.svg | vector |
| `antigravity` | https://www.google.com/s2/favicons?domain=antigravity.google&sz=128 | WebP, at most 128 px |
| `azure` | https://www.google.com/s2/favicons?domain=azure.microsoft.com&sz=128 | WebP, at most 128 px |
| `buck` | https://www.google.com/s2/favicons?domain=buck2.build&sz=128 | WebP, at most 128 px |
| `charm` | https://www.google.com/s2/favicons?domain=charm.sh&sz=128 | WebP, at most 128 px |
| `crush` | https://raw.githubusercontent.com/charmbracelet/crush/main/internal/ui/notification/crush-icon-solo.png | WebP, at most 128 px |
| `cua` | https://www.google.com/s2/favicons?domain=cua.ai&sz=128 | WebP, at most 128 px |
| `d2` | https://github.com/terrastruct.png | WebP, at most 128 px |
| `dbcli` | https://www.google.com/s2/favicons?domain=dbcli.com&sz=128 | WebP, at most 128 px |
| `depot` | https://www.google.com/s2/favicons?domain=depot.dev&sz=128 | WebP, at most 128 px |
| `devin` | https://www.google.com/s2/favicons?domain=devin.ai&sz=128 | WebP, at most 128 px |
| `droid` | https://factory.ai/favicon.svg | vector |
| `e2b` | https://www.google.com/s2/favicons?domain=e2b.dev&sz=128 | WebP, at most 128 px |
| `fx` | https://www.google.com/s2/favicons?domain=fx.wtf&sz=128 | WebP, at most 128 px |
| `goose` | https://raw.githubusercontent.com/block/goose/main/ui/desktop/src/images/icon.svg | vector |
| `hermes` | https://www.google.com/s2/favicons?domain=nousresearch.com&sz=128 | WebP, at most 128 px |
| `homey` | https://github.com/athombv.png | WebP, at most 128 px |
| `ibm-bob` | https://www.google.com/s2/favicons?domain=ibm.com&sz=128 | WebP, at most 128 px |
| `jj` | https://www.google.com/s2/favicons?domain=jj-vcs.github.io&sz=128 | WebP, at most 128 px |
| `kiro` | https://www.google.com/s2/favicons?domain=kiro.dev&sz=128 | WebP, at most 128 px |
| `llama-cpp` | https://github.com/ggml-org.png | WebP, at most 128 px |
| `mastra` | https://www.google.com/s2/favicons?domain=mastra.ai&sz=128 | WebP, at most 128 px |
| `mise` | https://www.google.com/s2/favicons?domain=mise.jdx.dev&sz=128 | WebP, at most 128 px |
| `omp` | https://www.google.com/s2/favicons?domain=ohmyposh.dev&sz=128 | WebP, at most 128 px |
| `openclaw` | https://www.google.com/s2/favicons?domain=openclaw.ai&sz=128 | WebP, at most 128 px |
| `opencode` | https://opencode.ai/favicon.svg | vector |
| `orbstack` | https://www.google.com/s2/favicons?domain=orbstack.dev&sz=128 | WebP, at most 128 px |
| `oxide` | https://www.google.com/s2/favicons?domain=oxide.computer&sz=128 | WebP, at most 128 px |
| `pack` | https://github.com/buildpacks.png | WebP, at most 128 px |
| `pi` | https://pi.dev/favicon.svg | vector |
| `pixi` | https://github.com/prefix-dev.png | WebP, at most 128 px |
| `poolside-icon` | https://www.google.com/s2/favicons?domain=poolside.ai&sz=128 | WebP, at most 128 px |
| `porter` | https://www.google.com/s2/favicons?domain=porter.run&sz=128 | WebP, at most 128 px |
| `prime-agent` | https://www.google.com/s2/favicons?domain=primeintellect.ai&sz=128 | WebP, at most 128 px |
| `yazi` | https://www.google.com/s2/favicons?domain=yazi-rs.github.io&sz=128 | WebP, at most 128 px |

## Generic tiles

No logo: Rex's own system glyphs and programs without a public mark. They are drawn in Compose as
green monospace initials on a dark tile (`>_` for `terminal`):
`btop`, `caffeinate`, `delta`, `fm`, `gitchanges`, `hunk`, `inspector`, `kakoune`, `log`, `maki`, `minimal`, `mole`, `opscope`, `rex`, `shell-container`, `sleep`, `sofka`, `terminal`, `top`, `vakna`.
