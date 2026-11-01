"""Attach Linoni's focused tools and migrate only known legacy scheduled tasks."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


PACKAGE_ID = "LNTOOL"
SOURCE_PACKAGE_ID = "N6GNQF"
AGENT_ID = "18CCXQ"
CAIYUN_MCP = "caiyun-weather__01M68B7R2REQWWAMPJ6KCQ8YTJ"
SKILLS = (
    "self-improving",
    "summarize",
    "healthfit-cn",
    "health-report",
    "health-score-pro",
    "personal-health-journal",
)

TOOLING_START = "<!-- octop-tooling:start -->"
TOOLING_END = "<!-- octop-tooling:end -->"
TOOLING_BLOCK = f"""\
{TOOLING_START}
## Linoni 工具与成长准则
- 技能的标准路径是 `/.octop/workspaces/18CCXQ/.octop/skills/<技能名>/SKILL.md`；执行技能相关任务时先直接 `read_file` 该路径。一次 glob 未命中不代表技能未安装，只有直接读取也失败时才可说明不可用。
- 使用 Self-Improving + Proactive Agent 的方法记录成长：只有用户明确纠正、重复出现的摩擦（至少两次）或已核验的发现，才写成“候选经验”；写清触发、纠正、可复用规则、证据、边界和待验证项。不要静默把一次偏好提升为永久规则，不要记录凭据、账号标识或精确健康读数。
- 健康建议优先使用 healthfit、health-score-pro、personal-health-journal；只依据卡片和工作区里的实际记录，说明趋势依据，给保守、可执行的生活方式建议；不做诊断，不推销补剂或产品；出现胸闷、气短、晕厥等红旗信号时建议及时线下就医，并说明建议不能替代专业诊疗。
- 新闻先用 summarize 做聚合、同事件去重、分组和核验；连接器原文优先，额度不足时可直读并核验一手媒体文章页，标注“连接器原文”或“站点直读”；每条保留实际可打开的原文 URL，禁止搜索页、图片和编造链接。
- 需要在会话中生成 HTML 时，先用可用 MCP 或连接器整理并校验数据，再生成自包含页面；图表数据先规范化，优先使用 AntV MCP 渲染，将返回图片安全嵌入页面，并提供可读的替代表格。没有渲染结果时要明确说明。
- 天气优先使用彩云天气 MCP 的实时、逐小时和预警数据；失败或城市无法解析时回退内置 get_weather，并注明来源与回退原因。
- 瑞幸咖啡按 `queryShopList` → `searchProductForMcp` → 按需 `switchProduct` → `previewOrder` → 用户确认 → `createOrder` → `queryOrderDetailInfo` 的顺序执行；下单前必须确认门店、饮品与规格、数量、到店自取方式和价格，未确认不得提交。只有工具明确返回成功订单及真实取餐码时才展示取餐码；失败、取消或仅预览时不得展示或猜测。除非工具结果可验证，否则不得声称已经下单。
{TOOLING_END}"""

SUMMARIZE_MARKER = "<!-- octop-summarize:v2 -->"
SUMMARIZE_MARKER_V1 = "<!-- octop-summarize:v1 -->"
CAIYUN_MARKER = "<!-- octop-caiyun:v2 -->"
CAIYUN_MARKER_V1 = "<!-- octop-caiyun:v1 -->"
MORNING_WEATHER_PROMPT_V1_SHA256 = (
    "7b65362a2a5e28c70d3db51907e886786f9f7eaeb07f1731d295ca96d08a9d21"
)
TOMORROW_WEATHER_PROMPT_V1_SHA256 = (
    "b9e7f524e72d61efce48a49298ab4f6479977a2542c65394eb8fc5b90b5e1140"
)
NEWS_PREFIX = f"""\
{SUMMARIZE_MARKER}
请先使用已加载的 summarize 技能执行“候选材料聚合 → 同事件去重 → 按主题分组 → 交叉核验 → 压缩输出”，再整理新闻。来源按以下顺序处理：
1. 优先使用腾讯新闻连接器实际返回的原文 URL，并标注“连接器原文”。
2. 连接器不可用、额度不足或未返回 URL 时，可用搜索/浏览工具定位并打开第一方媒体文章页；确认正文确实对应该事件后，使用文章页的 canonical URL，并标注“站点直读”。
3. 禁止使用搜索页、图片地址、转载聚合页、猜测或编造链接。两条路径都无法取得并实际打开核验的条目应跳过，并简要说明来源失败原因。

每条信息都必须保留至少一个实际可打开的原文 URL。

下面保留原有关注范围、GitHub 榜单和去重规则：
"""
INDUSTRY_PREFIX = f"""\
{SUMMARIZE_MARKER}
请先使用已加载的 summarize 技能对候选材料做聚合、同事件去重、分组和交叉核验，再编写下面的行业信号。优先使用腾讯新闻连接器返回的原文 URL，并标注“连接器原文”；连接器不可用或额度不足时，可直读并核验第一方媒体文章页，使用 canonical URL 并标注“站点直读”。禁止搜索页、图片、转载聚合页、猜测或编造链接。

下面保留原有格式、成熟度判断、证据强度和去重规则：
"""
WEEKLY_PROMPT_V1 = f"""\
{SUMMARIZE_MARKER_V1}
请使用 summarize 技能配合腾讯新闻连接器完成本周回顾。先按事件聚合重复报道并形成时间线，再整理关键变化、相互关联和下周值得追踪的线索。每条信息必须保留至少一个连接器实际返回、可直接打开的原文 URL，不得使用搜索页、图片地址、转载聚合页或编造链接；无法取得真实 URL 的条目不要输出。明确区分“已确认事实”“后续进展”“仍待核实的信息”，不要把厂商宣称写成已经可用。请按“本周总览 / 关键事件与时间线 / 重要变化 / 下周观察 / 来源”输出；每个事件说明发生了什么、为什么重要、下一步观察什么。"""
WEEKLY_PROMPT = f"""\
{SUMMARIZE_MARKER}
请使用 summarize 技能完成本周回顾。先按事件聚合重复报道并形成时间线，再整理关键变化、相互关联和下周值得追踪的线索。来源优先使用腾讯新闻连接器返回的原文 URL，并标注“连接器原文”；连接器不可用、额度不足或未返回 URL 时，可直读并核验第一方媒体文章页，使用 canonical URL 并标注“站点直读”。禁止使用搜索页、图片、转载聚合页、猜测或编造链接；无法实际打开核验的条目不要输出。明确区分“已确认事实”“后续进展”“仍待核实的信息”，不要把厂商宣称写成已经可用。请按“本周总览 / 关键事件与时间线 / 重要变化 / 下周观察 / 来源”输出；每个事件说明发生了什么、为什么重要、下一步观察什么。"""
MORNING_WEATHER_PROMPT = f"""\
{CAIYUN_MARKER}
请先根据当前日期、我最近明确的位置和记忆中的最新常住/出行地点确定今天查询的城市；若在北京与天津之间往返，按当天所在地查询；若只能确定到滨海等区县而天气服务无法精确匹配，改用所属城市（如天津）查询，不要猜测更细的位置；若仍无法确定城市，明确询问我。若今天包含滨海与北京两地行程，天气必须分别查询并列出两地结果。然后优先调用彩云天气 MCP（caiyun-weather）的实时天气、逐小时预报和预警，核对今天的降雨概率、最低/最高温、昼夜温差和短时变化；若彩云不可用或无法解析城市，再回退内置 get_weather(city, days=3)。不得编造预警。再使用飞书日程连接器整理今天的会议与日程，并结合我明确提到的待办、出行和活动形成整体安排。最终卡片只输出结果，不要写位置判断、查询城市、权限状态、连接器/MCP、工具调用、接口限流或回退过程；日程为空时只写“暂无已同步的日程安排”，不要解释权限。天气按地点分行，只保留天气、温度、降雨、穿衣和带伞结论。按时间列出日程，标记需要准备的材料、潜在冲突和需要提前确认的事项。请固定输出【今日安排】【天气提醒】【行动建议】三个部分，先讲整体安排，再讲天气结果，最后给出可执行建议。"""
TOMORROW_WEATHER_PROMPT = f"""\
{CAIYUN_MARKER}
请先根据当前日期、我最近明确的位置和记忆中的最新常住/出行地点确定查询城市；若在北京与天津之间往返，按当天所在地查询；若只能确定到滨海等区县而天气服务无法精确匹配，改用所属城市（如天津）查询，不要猜测更细的位置；若仍无法确定城市，明确询问我。若明天包含滨海与北京两地行程，天气必须分别查询并列出两地结果。然后优先调用彩云天气 MCP（caiyun-weather）的实时天气、逐小时预报和预警，重点核对明天的降雨概率、最低/最高温和降温幅度；若彩云不可用或无法解析城市，再回退内置 get_weather(city, days=2)。不得编造预警。再使用飞书日程连接器预览明天的会议与日程，并结合我明确提到的待办、出行和活动形成明天整体安排。最终卡片只输出结果，不要写位置判断、查询城市、权限状态、连接器/MCP、工具调用、接口限流或回退过程；日程为空时只写“暂无已同步的日程安排”，不要解释权限。若明日在滨海与北京之间移动，直接写明例如“明日上午在滨海，晚上返回北京”。天气按地点分行，只保留两地天气、温度、降雨、穿衣和带伞结论。按时间列出安排，标记需要提前准备的材料、时间冲突和待确认事项。请固定输出【明日安排】【天气与穿着】【今晚准备】三个部分，先讲安排，再讲天气结果，最后给出今晚能执行的动作。"""


@dataclass(frozen=True)
class TaskRule:
    legacy_sha256: str
    marker: str
    build_prompt: Callable[[str], str]
    add_caiyun: bool = False
    upgrade_v1: Callable[[str], str | None] | None = None


def _upgrade_with_tail(prompt: str, heading: str, prefix: str) -> str | None:
    if SUMMARIZE_MARKER_V1 not in prompt:
        return None
    before, separator, tail = prompt.partition(heading)
    if not before or not separator:
        return None
    return f"{prefix}\n\n{separator}{tail.lstrip()}"


def _upgrade_weekly(prompt: str) -> str | None:
    if _fingerprint(prompt) != _fingerprint(WEEKLY_PROMPT_V1):
        return None
    return WEEKLY_PROMPT


def _upgrade_caiyun(
    prompt: str,
    *,
    legacy_sha256: str,
    upgraded_prompt: str,
) -> str | None:
    if _fingerprint(prompt) != legacy_sha256:
        return None
    return upgraded_prompt


TASKS: dict[str, TaskRule] = {
    "cG0S6DS": TaskRule(
        legacy_sha256="77b002984219e96603e2ecc56133df694b4ee990d45374a2fabd0c0282d02ff6",
        marker=SUMMARIZE_MARKER,
        build_prompt=lambda old: f"{NEWS_PREFIX}\n\n{old.strip()}",
        upgrade_v1=lambda prompt: _upgrade_with_tail(
            prompt,
            "下面保留原有关注范围、GitHub 榜单和去重规则：",
            NEWS_PREFIX,
        ),
    ),
    "cQC7N80": TaskRule(
        legacy_sha256="6c2d1819a0123d7b1cb6e24aebcf567d67ae00e6d70dbb31c416b89036583d8b",
        marker=SUMMARIZE_MARKER,
        build_prompt=lambda _old: WEEKLY_PROMPT,
        upgrade_v1=_upgrade_weekly,
    ),
    "cY9D634": TaskRule(
        legacy_sha256="b46bbe61a89146c44030dd54f1d8e858c3fdb38c23788bf14e171f56825f700b",
        marker=SUMMARIZE_MARKER,
        build_prompt=lambda old: f"{INDUSTRY_PREFIX}\n\n{old.strip()}",
        upgrade_v1=lambda prompt: _upgrade_with_tail(
            prompt,
            "下面保留原有格式、成熟度判断、证据强度和去重规则：",
            INDUSTRY_PREFIX,
        ),
    ),
    "c8W9REG": TaskRule(
        legacy_sha256="0b5faddd8aaedff36d915c983a987c5d5ed06283ec5bc8b5a64ef952bf4007a9",
        marker=CAIYUN_MARKER,
        build_prompt=lambda _old: MORNING_WEATHER_PROMPT,
        add_caiyun=True,
        upgrade_v1=lambda prompt: _upgrade_caiyun(
            prompt,
            legacy_sha256=MORNING_WEATHER_PROMPT_V1_SHA256,
            upgraded_prompt=MORNING_WEATHER_PROMPT,
        ),
    ),
    "cV2AN3S": TaskRule(
        legacy_sha256="c6c34ed60d0d7bad27ac490224536f221095936f5794ff5ed5c7af2ead9ed826",
        marker=CAIYUN_MARKER,
        build_prompt=lambda _old: TOMORROW_WEATHER_PROMPT,
        add_caiyun=True,
        upgrade_v1=lambda prompt: _upgrade_caiyun(
            prompt,
            legacy_sha256=TOMORROW_WEATHER_PROMPT_V1_SHA256,
            upgraded_prompt=TOMORROW_WEATHER_PROMPT,
        ),
    ),
}


def _fingerprint(prompt: str) -> str:
    return hashlib.sha256(prompt.strip().encode("utf-8")).hexdigest()


def _replace_managed_block(text: str, start: str, end: str, block: str) -> tuple[str, bool]:
    if start in text:
        if end not in text:
            raise RuntimeError(f"incomplete managed block: {start}")
        prefix, remainder = text.split(start, 1)
        _, suffix = remainder.split(end, 1)
        updated = prefix + block + suffix
    else:
        if end in text:
            raise RuntimeError(f"incomplete managed block: {end}")
        updated = text.rstrip() + "\n\n" + block + "\n"
    return updated, updated != text


def _ensure_skill_package(home: Path) -> tuple[bool, bool]:
    source = home / "skill-packages" / SOURCE_PACKAGE_ID / "skills"
    missing = [name for name in SKILLS if not (source / name).is_dir()]
    if missing:
        raise RuntimeError(f"source skills missing under {source}: {', '.join(missing)}")

    root = home / "skill-packages"
    target = root / PACKAGE_ID
    if all((target / "skills" / name).is_dir() for name in SKILLS):
        return False, True

    root.mkdir(parents=True, exist_ok=True)
    staging = root / f".{PACKAGE_ID}-{uuid.uuid4().hex}.tmp"
    try:
        for name in SKILLS:
            shutil.copytree(source / name, staging / "skills" / name)
        if target.exists():
            shutil.rmtree(target)
        staging.replace(target)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return True, True


def _ensure_skill_package_row(conn: sqlite3.Connection, user_id: int) -> bool:
    row = conn.execute(
        "SELECT 1 FROM skill_packages WHERE skill_package_id = ?",
        (PACKAGE_ID,),
    ).fetchone()
    if row is not None:
        return False
    now = str(int(time.time()))
    conn.execute(
        """
        INSERT INTO skill_packages (
            skill_package_id, name, description, created_by, skill_count,
            icon_name, icon_url, created_at, updated_at, copy_policy,
            allow_download, shared
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            PACKAGE_ID,
            "Linoni 工具与健康技能",
            "智能摘要、Self-Improving、健康分析、健康报告与个人健康档案。",
            str(user_id),
            len(SKILLS),
            "sparkles",
            "",
            now,
            now,
            "snapshot",
            1,
            0,
        ),
    )
    return True


def _resolve_workspace_dir(home: Path, config_json: str | None) -> Path | None:
    try:
        config = json.loads(config_json or "{}")
    except json.JSONDecodeError as exc:
        raise RuntimeError("invalid agent config_json") from exc
    raw_workspace = str(config.get("workspace_dir") or "").strip()
    if not raw_workspace:
        return None
    workspace = Path(raw_workspace).expanduser()
    backend = config.get("backend")
    root_dir = (
        str(backend.get("root_dir") or "").strip()
        if isinstance(backend, dict)
        else ""
    )
    if workspace.is_absolute() and root_dir:
        root = Path(root_dir).expanduser()
        try:
            workspace.relative_to(root)
        except ValueError:
            workspace = root / str(workspace).lstrip("/\\")
    elif not workspace.is_absolute():
        workspace = home / workspace
    return workspace


def _copy_skills_to_workspace(
    conn: sqlite3.Connection,
    home: Path,
    package_dir: Path,
) -> dict[str, str]:
    row = conn.execute(
        "SELECT config_json FROM agents WHERE agent_id = ?",
        (AGENT_ID,),
    ).fetchone()
    if row is None:
        return {"status": "agent_missing"}
    workspace = _resolve_workspace_dir(home, row[0])
    if workspace is None:
        return {"status": "workspace_not_configured"}

    skills_dir = workspace / ".octop" / "skills"
    results: dict[str, str] = {}
    for name in SKILLS:
        source = package_dir / "skills" / name
        destination = skills_dir / name
        if (destination / "SKILL.md").is_file():
            results[name] = "already_present"
            continue
        if destination.exists():
            results[name] = "skipped_conflict"
            continue
        skills_dir.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, destination)
        results[name] = "copied"
    return {"status": "ok", "workspace": str(workspace), "skills": results}


def _json_string_list(raw: str | None, *, label: str) -> list[str]:
    try:
        value = json.loads(raw or "[]")
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"invalid {label} JSON") from exc
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise RuntimeError(f"{label} must be a JSON string array")
    return value


def _migrate_agent(conn: sqlite3.Connection) -> dict[str, object]:
    row = conn.execute(
        "SELECT user_id, system_prompt, skill_package_ids FROM agents WHERE agent_id = ?",
        (AGENT_ID,),
    ).fetchone()
    if row is None:
        raise RuntimeError(f"agent not found: {AGENT_ID}")
    user_id, system_prompt, package_ids_raw = row

    packages = _json_string_list(package_ids_raw, label="skill_package_ids")
    package_added = PACKAGE_ID not in packages
    if package_added:
        packages.append(PACKAGE_ID)
    prompt = str(system_prompt or "")
    prompt, prompt_changed = _replace_managed_block(
        prompt,
        TOOLING_START,
        TOOLING_END,
        TOOLING_BLOCK,
    )
    if package_added or prompt_changed:
        conn.execute(
            "UPDATE agents SET skill_package_ids = ?, system_prompt = ?, updated_at = ? "
            "WHERE agent_id = ?",
            (
                json.dumps(packages, ensure_ascii=False, separators=(",", ":")),
                prompt,
                int(time.time()),
                AGENT_ID,
            ),
        )
    return {
        "package_added": package_added,
        "prompt_changed": prompt_changed,
    }


def _merge_mcp(raw: str | None, server_id: str) -> tuple[str, bool]:
    servers = _json_string_list(raw, label="mcp_servers")
    if server_id in servers:
        return json.dumps(servers, ensure_ascii=False, separators=(",", ":")), False
    servers.append(server_id)
    return json.dumps(servers, ensure_ascii=False, separators=(",", ":")), True


def _migrate_tasks(conn: sqlite3.Connection) -> dict[str, str]:
    results: dict[str, str] = {}
    for cron_id, rule in TASKS.items():
        row = conn.execute(
            "SELECT prompt, mcp_servers FROM cron_jobs WHERE cron_id = ?",
            (cron_id,),
        ).fetchone()
        if row is None:
            results[cron_id] = "missing"
            continue
        prompt, mcp_servers = row
        prompt = str(prompt or "")
        if rule.marker in prompt:
            results[cron_id] = "already_migrated"
            continue
        if (
            (SUMMARIZE_MARKER_V1 in prompt or CAIYUN_MARKER_V1 in prompt)
            and rule.upgrade_v1 is not None
        ):
            upgraded = rule.upgrade_v1(prompt)
            if upgraded is None:
                results[cron_id] = "skipped_unrecognized_managed_prompt"
                continue
            new_mcp = mcp_servers
            if rule.add_caiyun:
                new_mcp, _ = _merge_mcp(mcp_servers, CAIYUN_MCP)
            conn.execute(
                "UPDATE cron_jobs SET prompt = ?, mcp_servers = ?, updated_at = ? "
                "WHERE cron_id = ?",
                (upgraded, new_mcp, int(time.time()), cron_id),
            )
            results[cron_id] = "migrated_v2"
            continue
        if _fingerprint(prompt) != rule.legacy_sha256:
            results[cron_id] = "skipped_user_modified"
            continue

        new_prompt = rule.build_prompt(prompt)
        new_mcp = mcp_servers
        if rule.add_caiyun:
            new_mcp, _ = _merge_mcp(mcp_servers, CAIYUN_MCP)
        conn.execute(
            "UPDATE cron_jobs SET prompt = ?, mcp_servers = ?, updated_at = ? WHERE cron_id = ?",
            (new_prompt, new_mcp, int(time.time()), cron_id),
        )
        results[cron_id] = "migrated"
    return results


def migrate(home: Path, db_path: Path) -> dict[str, object]:
    if not db_path.is_file():
        raise RuntimeError(f"database not found: {db_path}")
    package_created, package_ready = _ensure_skill_package(home)
    package_dir = home / "skill-packages" / PACKAGE_ID
    with sqlite3.connect(db_path) as conn:
        agent = conn.execute(
            "SELECT user_id FROM agents WHERE agent_id = ?",
            (AGENT_ID,),
        ).fetchone()
        if agent is None:
            raise RuntimeError(f"agent not found: {AGENT_ID}")
        row_created = _ensure_skill_package_row(conn, int(agent[0]))
        agent_result = _migrate_agent(conn)
        task_results = _migrate_tasks(conn)
        workspace_result = _copy_skills_to_workspace(conn, home, package_dir)
        conn.commit()
    return {
        "package_id": PACKAGE_ID,
        "skills": list(SKILLS),
        "package_files_created": package_created,
        "package_created": row_created,
        "package_ready": package_ready,
        "agent": agent_result,
        "workspace": workspace_result,
        "tasks": task_results,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--home", type=Path)
    parser.add_argument("--db", type=Path)
    args = parser.parse_args()
    db_path = args.db or Path(os.environ.get("OCTOP_DB_PATH", "/opt/octop/octop.db"))
    home = args.home or Path(os.environ.get("OCTOP_HOME", str(db_path.parent)))
    print(json.dumps(migrate(home, db_path), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
