from pathlib import Path
import re
import sys


MANAGED_START = "# BEGIN DINGDANG OPS MANAGEMENT WEB"
MANAGED_END = "# END DINGDANG OPS MANAGEMENT WEB"
EXPERT_DOMAIN = "bb.chinacedar.top"
DOMAIN_PATTERN = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$")


def render(template_file: str, domain: str, port: int) -> str:
    normalized_domain = domain.strip().lower().rstrip(".")
    if (
        not DOMAIN_PATTERN.fullmatch(normalized_domain)
        or normalized_domain == EXPERT_DOMAIN
        or normalized_domain == "localhost"
    ):
        raise ValueError("ops_management_requires_a_dedicated_domain")
    if not 1 <= port <= 65535 or port in {8787, 8790}:
        raise ValueError("ops_management_requires_an_isolated_port")
    template = Path(template_file).read_text("utf-8")
    if template.count(MANAGED_START) != 1 or template.count(MANAGED_END) != 1:
        raise ValueError("ops_management_caddy_markers_invalid")
    return (
        template.replace("__OPS_MANAGEMENT_DOMAIN__", normalized_domain)
        .replace("__OPS_MANAGEMENT_PORT__", str(port))
        .rstrip()
        + "\n"
    )


def merge(caddyfile: str, template_file: str, domain: str, port: int) -> None:
    path = Path(caddyfile)
    content = path.read_text("utf-8")
    managed = render(template_file, domain, port)
    start = content.find(MANAGED_START)
    end = content.find(MANAGED_END)
    if (start < 0) != (end < 0) or content.count(MANAGED_START) > 1 or content.count(MANAGED_END) > 1:
        raise ValueError("ops_management_caddy_managed_block_corrupt")
    if start >= 0:
        end += len(MANAGED_END)
        while end < len(content) and content[end] in "\r\n":
            end += 1
        path.write_text(content[:start] + managed + content[end:], "utf-8")
        return

    site_pattern = re.compile(
        rf"(?m)^\s*{re.escape(domain.strip().lower().rstrip('.'))}\s*\{{"
    )
    if site_pattern.search(content.lower()):
        raise ValueError("ops_management_caddy_site_conflict")
    separator = "" if not content or content.endswith("\n\n") else "\n"
    path.write_text(content + separator + managed, "utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: merge_caddy.py Caddyfile template domain port"
        )
    merge(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]))
