from pathlib import Path
import re
import sys


LEGACY_V9_BLOCK = re.compile(
    r"(?m)^    handle_path /v9-ops/\* \{\r?\n"
    r"(?:^        .*\r?\n)*"
    r"^    \}\r?\n"
)


def merge(caddyfile: str, snippet_file: str) -> None:
    path = Path(caddyfile)
    content = path.read_text("utf-8")
    snippet = Path(snippet_file).read_text("utf-8").rstrip() + "\n\n"
    site = "bb.chinacedar.top:2305 {"
    site_start = content.find(site)
    if site_start < 0:
        raise SystemExit("caddy_v9_site_missing")
    fallback = content.find("    handle {", site_start)
    next_site = content.find("\n}\n", site_start)
    if fallback < 0 or next_site < 0 or fallback > next_site:
        raise SystemExit("caddy_v9_fallback_missing")
    managed_start = content.find("    @v9_ops_root path /v9-ops", site_start, fallback)
    if managed_start >= 0:
        path.write_text(
            content[:managed_start] + snippet + content[fallback:], "utf-8"
        )
        return
    site_content = content[site_start:next_site]
    legacy = LEGACY_V9_BLOCK.search(site_content)
    if legacy:
        legacy_start = site_start + legacy.start()
        legacy_end = site_start + legacy.end()
        path.write_text(
            content[:legacy_start] + snippet + content[legacy_end:], "utf-8"
        )
        return
    path.write_text(content[:fallback] + snippet + content[fallback:], "utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: merge_caddy.py Caddyfile snippet")
    merge(sys.argv[1], sys.argv[2])
