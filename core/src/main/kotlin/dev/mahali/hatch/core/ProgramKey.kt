package dev.mahali.hatch.core

/** Icon key, named like Rex's own program glyphs ("claude", "git"). */
@JvmInline public value class ProgramKey(public val value: String) {
    public companion object {
        public val Terminal: ProgramKey = ProgramKey("terminal")
    }
}

public fun programKey(process: String): ProgramKey {
    val name = process.trim().substringAfterLast('/').removePrefix("-").lowercase()
    val key = aliases[name] ?: name.takeIf { it in known } ?: versioned(name)
    return if (key != null) ProgramKey(key) else ProgramKey.Terminal
}

private fun versioned(name: String): String? = when {
    pythonVersion.matches(name) -> "python"
    nodeVersion.matches(name) -> "node"
    else -> null
}

private val pythonVersion = Regex("python[0-9.]+")
private val nodeVersion = Regex("node[0-9.]+")

/** Icons that exist; matches the design page's "Full catalogue". */
private val known: Set<String> = setOf(
    "amp", "android", "antigravity", "apple-container", "azure", "bash", "bat", "bazel", "boundary",
    "brew", "browser", "btop", "buck", "buildkite", "bun", "butler", "caddy", "caffeinate", "charm",
    "clang", "claude", "cloudflare", "coder", "codex", "consul", "copilot", "crush", "cua", "cursor",
    "d2", "dart", "datadog", "dbcli", "delta", "depot", "devin", "docker", "dotnet", "droid", "duckdb",
    "e2b", "elixir", "emacs", "expo", "fish", "flutter", "fm", "fx", "gemini", "git", "gitchanges",
    "github", "gitlab", "gnu", "go", "goose", "gradle", "grok", "hermes", "heroku", "homey", "htop",
    "hugging-face", "hunk", "hx", "ibm-bob", "inspector", "jj", "julia", "junie", "jupyter", "k9s",
    "kakoune", "kiro", "kube", "llama-cpp", "log", "maki", "mastra", "meta", "micro", "minimal", "mise",
    "mistral", "modal", "mole", "moon", "nano", "neovim", "nextflow", "nix", "node", "nomad", "nushell",
    "ollama", "omp", "openclaw", "opencode", "opscope", "orbstack", "oxide", "pack", "packer", "php",
    "pi", "pixi", "pnpm", "podman", "poolside-icon", "porter", "postgres", "prime-agent", "proto",
    "proton-drive", "proton-pass", "python", "railway", "rclone", "redis", "render", "rex", "ruby",
    "rust", "sail", "sanity", "shell-container", "shopify", "sleep", "snowflake", "sofka", "sqlite",
    "stripe", "swift", "tailscale", "task", "terminal", "terraform", "top", "turborepo",
    "unreal-engine", "uv", "vagrant", "vakna", "vault", "vercel", "vim", "vite", "vite-plus",
    "waypoint", "xcode", "yazi", "zig", "zsh"
)

private val aliases: Map<String, String> = mapOf(
    "nvim" to "neovim", "vi" to "vim", "emacsclient" to "emacs", "kak" to "kakoune", "helix" to "hx",
    "npm" to "node", "npx" to "node", "bunx" to "bun", "pnpx" to "pnpm",
    "ipython" to "python", "pip" to "python", "pip3" to "python", "uvx" to "uv",
    "cargo" to "rust", "rustc" to "rust", "rustup" to "rust",
    "gradlew" to "gradle", "make" to "gnu", "gmake" to "gnu", "gcc" to "gnu", "cc" to "clang",
    "lazygit" to "git", "tig" to "git", "gitui" to "git", "gh" to "github", "glab" to "gitlab",
    "docker-compose" to "docker", "lazydocker" to "docker", "kubectl" to "kube", "kubectx" to "kube",
    "psql" to "postgres", "pgcli" to "postgres", "redis-cli" to "redis", "sqlite3" to "sqlite",
    "tail" to "log", "less" to "log", "journalctl" to "log", "btm" to "btop",
    "cursor-agent" to "cursor", "claude-code" to "claude", "vibe" to "mistral", "llama-server" to "llama-cpp", "llama-cli" to "llama-cpp",
    "irb" to "ruby", "bundle" to "ruby", "rails" to "ruby", "composer" to "php",
    "iex" to "elixir", "mix" to "elixir", "swiftc" to "swift", "xcodebuild" to "xcode",
    "nu" to "nushell", "sh" to "terminal", "dash" to "terminal",
)
