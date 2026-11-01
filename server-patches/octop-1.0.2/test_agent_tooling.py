from __future__ import annotations

import importlib.util
import json
import sqlite3
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest import mock


def _load_module():
    path = Path(__file__).with_name("migrate_agent_tooling.py")
    spec = importlib.util.spec_from_file_location("migrate_agent_tooling", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


migration = _load_module()


class AgentToolingMigrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.db_path = self.home / "octop.db"
        source = self.home / "skill-packages" / migration.SOURCE_PACKAGE_ID / "skills"
        for name in migration.SKILLS:
            (source / name).mkdir(parents=True)
            (source / name / "SKILL.md").write_text(f"# {name}\n", encoding="utf-8")
        (source / "unrelated").mkdir()
        (source / "unrelated" / "SKILL.md").write_text("# unrelated\n", encoding="utf-8")

        with sqlite3.connect(self.db_path) as conn:
            conn.executescript(
                """
                CREATE TABLE agents (
                    agent_id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    system_prompt TEXT NOT NULL,
                    skill_package_ids TEXT NOT NULL,
                    config_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                CREATE TABLE cron_jobs (
                    cron_id TEXT PRIMARY KEY,
                    prompt TEXT NOT NULL,
                    mcp_servers TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                CREATE TABLE skill_packages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    skill_package_id TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    created_by TEXT,
                    skill_count INTEGER NOT NULL,
                    icon_name TEXT,
                    icon_url TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    copy_policy TEXT NOT NULL,
                    allow_download INTEGER NOT NULL,
                    shared INTEGER NOT NULL
                );
                """
            )
            conn.execute(
                "INSERT INTO agents VALUES (?, ?, ?, ?, ?, ?)",
                (
                    migration.AGENT_ID,
                    2,
                    "# Persona: ENFJ\n\nKeep this existing persona.",
                    '["EXISTING"]',
                    json.dumps(
                        {
                            "workspace_dir": "/.octop/workspaces/tooling-agent",
                            "backend": {
                                "type": "local_shell",
                                "virtual_mode": True,
                                "root_dir": str(self.home / "agent-root"),
                            },
                        }
                    ),
                    1,
                ),
            )
            conn.execute(
                "INSERT INTO skill_packages "
                "(skill_package_id, name, description, created_by, skill_count, "
                "icon_name, icon_url, created_at, updated_at, copy_policy, "
                "allow_download, shared) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    migration.SOURCE_PACKAGE_ID,
                    "source",
                    "source package",
                    "2",
                    1,
                    None,
                    None,
                    "1",
                    "1",
                    "snapshot",
                    1,
                    0,
                ),
            )
            self.old_prompts = {
                "cG0S6DS": "old daily news",
                "cQC7N80": "old weekly news",
                "cY9D634": "old industry signal",
                "c8W9REG": "old morning weather",
                "cV2AN3S": "old tomorrow weather",
            }
            for cron_id, prompt in self.old_prompts.items():
                servers = (
                    json.dumps(["feishu-cli__existing"])
                    if cron_id in {"c8W9REG", "cV2AN3S"}
                    else "[]"
                )
                conn.execute(
                    "INSERT INTO cron_jobs VALUES (?, ?, ?, ?)",
                    (cron_id, prompt, servers, 1),
                )
            conn.commit()

        self.original_tasks = dict(migration.TASKS)
        self.addCleanup(self._restore_tasks)
        for cron_id, prompt in self.old_prompts.items():
            migration.TASKS[cron_id] = replace(
                migration.TASKS[cron_id],
                legacy_sha256=migration._fingerprint(prompt),
            )

    def _restore_tasks(self) -> None:
        migration.TASKS.clear()
        migration.TASKS.update(self.original_tasks)

    def test_migrates_only_selected_skills_and_is_idempotent(self) -> None:
        first = migration.migrate(self.home, self.db_path)
        second = migration.migrate(self.home, self.db_path)

        package_skills = {
            path.name
            for path in (
                self.home / "skill-packages" / migration.PACKAGE_ID / "skills"
            ).iterdir()
        }
        self.assertEqual(set(migration.SKILLS), package_skills)
        self.assertNotIn("unrelated", package_skills)
        self.assertTrue(first["package_files_created"])
        self.assertFalse(second["package_files_created"])
        self.assertTrue(first["agent"]["package_added"])
        self.assertFalse(second["agent"]["package_added"])
        self.assertTrue(first["agent"]["prompt_changed"])
        self.assertFalse(second["agent"]["prompt_changed"])
        self.assertEqual(
            {name: "copied" for name in migration.SKILLS},
            first["workspace"]["skills"],
        )
        self.assertEqual(
            {name: "already_present" for name in migration.SKILLS},
            second["workspace"]["skills"],
        )
        self.assertEqual(
            {"already_migrated"},
            set(second["tasks"].values()),
        )

        with sqlite3.connect(self.db_path) as conn:
            package = conn.execute(
                "SELECT skill_count, shared FROM skill_packages WHERE skill_package_id = ?",
                (migration.PACKAGE_ID,),
            ).fetchone()
            agent = conn.execute(
                "SELECT system_prompt, skill_package_ids FROM agents WHERE agent_id = ?",
                (migration.AGENT_ID,),
            ).fetchone()
            weather = conn.execute(
                "SELECT prompt, mcp_servers FROM cron_jobs WHERE cron_id = 'c8W9REG'"
            ).fetchone()

        self.assertEqual((6, 0), package)
        self.assertIn("Keep this existing persona.", agent[0])
        self.assertEqual(1, agent[0].count(migration.TOOLING_START))
        self.assertIn(
            "/.octop/workspaces/18CCXQ/.octop/skills/<技能名>/SKILL.md",
            agent[0],
        )
        self.assertIn("queryOrderDetailInfo", agent[0])
        self.assertIn("真实取餐码", agent[0])
        self.assertEqual(["EXISTING", migration.PACKAGE_ID], json.loads(agent[1]))
        self.assertIn(migration.CAIYUN_MARKER, weather[0])
        self.assertEqual(
            ["feishu-cli__existing", migration.CAIYUN_MCP],
            json.loads(weather[1]),
        )
        workspace_skills = (
            self.home
            / "agent-root"
            / ".octop"
            / "workspaces"
            / "tooling-agent"
            / ".octop"
            / "skills"
        )
        self.assertEqual(
            set(migration.SKILLS),
            {path.name for path in workspace_skills.iterdir()},
        )

    def test_does_not_overwrite_a_user_modified_task(self) -> None:
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "UPDATE cron_jobs SET prompt = ? WHERE cron_id = 'cG0S6DS'",
                ("my custom prompt",),
            )
            conn.commit()

        result = migration.migrate(self.home, self.db_path)

        with sqlite3.connect(self.db_path) as conn:
            prompt = conn.execute(
                "SELECT prompt FROM cron_jobs WHERE cron_id = 'cG0S6DS'"
            ).fetchone()[0]
        self.assertEqual("skipped_user_modified", result["tasks"]["cG0S6DS"])
        self.assertEqual("my custom prompt", prompt)

    def test_upgrades_v1_news_prompts_and_preserves_task_tail(self) -> None:
        daily_v1 = (
            f"{migration.SUMMARIZE_MARKER_V1}\n"
            "old connector-only rules\n\n"
            "下面保留原有关注范围、GitHub 榜单和去重规则：\n\n"
            "my retained daily scope"
        )
        industry_v1 = (
            f"{migration.SUMMARIZE_MARKER_V1}\n"
            "old connector-only rules\n\n"
            "下面保留原有格式、成熟度判断、证据强度和去重规则：\n\n"
            "my retained industry format"
        )
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "UPDATE cron_jobs SET prompt = ? WHERE cron_id = 'cG0S6DS'",
                (daily_v1,),
            )
            conn.execute(
                "UPDATE cron_jobs SET prompt = ? WHERE cron_id = 'cY9D634'",
                (industry_v1,),
            )
            conn.execute(
                "UPDATE cron_jobs SET prompt = ? WHERE cron_id = 'cQC7N80'",
                (migration.WEEKLY_PROMPT_V1,),
            )
            conn.commit()

        result = migration.migrate(self.home, self.db_path)

        with sqlite3.connect(self.db_path) as conn:
            prompts = dict(
                conn.execute(
                    "SELECT cron_id, prompt FROM cron_jobs "
                    "WHERE cron_id IN ('cG0S6DS', 'cQC7N80', 'cY9D634')"
                )
            )
        self.assertEqual("migrated_v2", result["tasks"]["cG0S6DS"])
        self.assertEqual("migrated_v2", result["tasks"]["cQC7N80"])
        self.assertEqual("migrated_v2", result["tasks"]["cY9D634"])
        for prompt in prompts.values():
            self.assertIn(migration.SUMMARIZE_MARKER, prompt)
            self.assertNotIn(migration.SUMMARIZE_MARKER_V1, prompt)
            self.assertIn("站点直读", prompt)
        self.assertIn("my retained daily scope", prompts["cG0S6DS"])
        self.assertIn("my retained industry format", prompts["cY9D634"])

    def test_does_not_upgrade_a_modified_v1_weekly_prompt(self) -> None:
        modified = migration.WEEKLY_PROMPT_V1.replace(
            "下周观察",
            "下周观察（我的自定义顺序）",
        )
        with sqlite3.connect(self.db_path) as conn:
            conn.execute(
                "UPDATE cron_jobs SET prompt = ? WHERE cron_id = 'cQC7N80'",
                (modified,),
            )
            conn.commit()

        result = migration.migrate(self.home, self.db_path)

        with sqlite3.connect(self.db_path) as conn:
            prompt = conn.execute(
                "SELECT prompt FROM cron_jobs WHERE cron_id = 'cQC7N80'"
            ).fetchone()[0]
        self.assertEqual(
            "skipped_unrecognized_managed_prompt",
            result["tasks"]["cQC7N80"],
        )
        self.assertEqual(modified, prompt)

    def test_exact_v1_weather_prompt_can_upgrade_to_v2(self) -> None:
        old_prompt = f"{migration.CAIYUN_MARKER_V1}\nold weather prompt"

        with mock.patch.object(
            migration,
            "_fingerprint",
            return_value=migration.TOMORROW_WEATHER_PROMPT_V1_SHA256,
        ):
            upgraded = migration._upgrade_caiyun(
                old_prompt,
                legacy_sha256=migration.TOMORROW_WEATHER_PROMPT_V1_SHA256,
                upgraded_prompt=migration.TOMORROW_WEATHER_PROMPT,
            )

        self.assertEqual(migration.TOMORROW_WEATHER_PROMPT, upgraded)
        self.assertIn(migration.CAIYUN_MARKER, upgraded or "")
        self.assertNotIn(migration.CAIYUN_MARKER_V1, upgraded or "")
        self.assertIn("明日上午在滨海，晚上返回北京", upgraded or "")
        self.assertIn("不要写位置判断", upgraded or "")


if __name__ == "__main__":
    unittest.main()
