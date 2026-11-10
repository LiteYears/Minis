#!/usr/bin/env python3
"""
Minis Data Studio — Interactive SQLite / CSV / TSV / JSON / JSONL Web Canvas
Zero external dependencies (Python 3 standard library only: sqlite3, csv, json, http.server).
Integrates directly with OpenMinis `window.minis` Bidirectional Agent Bridge.
"""

import argparse
import csv
import html
import io
import json
import math
import os
import re
import sqlite3
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def sanitize_identifier(name: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_]", "_", (name or "").strip())
    if not cleaned or cleaned[0].isdigit():
        cleaned = "col_" + cleaned
    return cleaned.lower()


def infer_value(val: str):
    if val is None:
        return None
    s = val.strip()
    if s == "" or s.lower() in ("null", "none", "na", "n/a", "nan"):
        return None
    try:
        if "." not in s and "e" not in s.lower():
            return int(s)
        f = float(s)
        if math.isfinite(f):
            return f
    except ValueError:
        pass
    return val


class DataStudioEngine:
    def __init__(self, source_path: str, port: int, title: str = ""):
        self.source_path = os.path.abspath(source_path)
        self.port = port
        self.title = title or os.path.basename(self.source_path) or "Minis Data Studio"
        self.is_native_sqlite = False
        self.db_path = f"/tmp/minis-datastudio-{port}.sqlite"
        self._prepare_database()

    def _prepare_database(self):
        if not os.path.exists(self.source_path):
            raise FileNotFoundError(f"Source path not found: {self.source_path}")

        if os.path.isdir(self.source_path):
            if os.path.exists(self.db_path):
                os.remove(self.db_path)
            conn = sqlite3.connect(self.db_path)
            try:
                loaded = 0
                for fname in sorted(os.listdir(self.source_path)):
                    fpath = os.path.join(self.source_path, fname)
                    if not os.path.isfile(fpath):
                        continue
                    ext = os.path.splitext(fname)[1].lower()
                    tname = sanitize_identifier(os.path.splitext(fname)[0])
                    if ext in (".csv", ".tsv"):
                        self._import_delimited(conn, fpath, tname, "\t" if ext == ".tsv" else ",")
                        loaded += 1
                    elif ext in (".json", ".jsonl"):
                        self._import_json(conn, fpath, tname)
                        loaded += 1
                if loaded == 0:
                    conn.execute("CREATE TABLE empty_workspace (note TEXT)")
                    conn.execute("INSERT INTO empty_workspace VALUES ('No CSV/JSON files found in directory')")
                    conn.commit()
            finally:
                conn.close()
            return

        ext = os.path.splitext(self.source_path)[1].lower()
        if ext in (".db", ".sqlite", ".sqlite3") or self._has_sqlite_header(self.source_path):
            self.db_path = self.source_path
            self.is_native_sqlite = True
            return

        if os.path.exists(self.db_path):
            os.remove(self.db_path)

        conn = sqlite3.connect(self.db_path)
        try:
            stem = sanitize_identifier(os.path.splitext(os.path.basename(self.source_path))[0])
            table_name = stem if stem and stem != "col_" else "dataset"
            if ext == ".tsv":
                self._import_delimited(conn, self.source_path, table_name, "\t")
            elif ext in (".json", ".jsonl"):
                self._import_json(conn, self.source_path, table_name)
            else:
                self._import_delimited(conn, self.source_path, table_name, ",")
        finally:
            conn.close()

    @staticmethod
    def _has_sqlite_header(path: str) -> bool:
        try:
            with open(path, "rb") as f:
                return f.read(16).startswith(b"SQLite format 3")
        except Exception:
            return False

    def _import_delimited(self, conn: sqlite3.Connection, path: str, table_name: str, delimiter: str):
        with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
            sample = f.read(8192)
            f.seek(0)
            try:
                dialect = csv.Sniffer().sniff(sample, delimiters=[delimiter, ",", "\t", ";", "|"])
            except Exception:
                dialect = csv.excel_tab if delimiter == "\t" else csv.excel
            reader = csv.reader(f, dialect)
            rows = list(reader)

        if not rows:
            conn.execute(f'CREATE TABLE "{table_name}" (id INTEGER PRIMARY KEY)')
            conn.commit()
            return

        raw_headers = rows[0]
        seen = {}
        headers = []
        for idx, h in enumerate(raw_headers):
            base = sanitize_identifier(h or f"col_{idx + 1}")
            cnt = seen.get(base, 0)
            seen[base] = cnt + 1
            headers.append(base if cnt == 0 else f"{base}_{cnt + 1}")

        data_rows = rows[1:]
        col_defs = ", ".join(f'"{c}"' for c in headers)
        placeholders = ", ".join("?" for _ in headers)
        conn.execute(f'DROP TABLE IF EXISTS "{table_name}"')
        conn.execute(f'CREATE TABLE "{table_name}" ({col_defs})')

        batch = []
        for r in data_rows:
            if not any(cell.strip() for cell in r):
                continue
            padded = (r + [None] * len(headers))[: len(headers)]
            batch.append([infer_value(c) if isinstance(c, str) else c for c in padded])
            if len(batch) >= 2000:
                conn.executemany(f'INSERT INTO "{table_name}" VALUES ({placeholders})', batch)
                batch.clear()
        if batch:
            conn.executemany(f'INSERT INTO "{table_name}" VALUES ({placeholders})', batch)
        conn.commit()

    def _import_json(self, conn: sqlite3.Connection, path: str, table_name: str):
        records = []
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read().strip()
            if not content:
                conn.execute(f'CREATE TABLE "{table_name}" (id INTEGER PRIMARY KEY)')
                conn.commit()
                return
            try:
                parsed = json.loads(content)
                if isinstance(parsed, list):
                    records = parsed
                elif isinstance(parsed, dict):
                    list_vals = [v for v in parsed.values() if isinstance(v, list)]
                    records = list_vals[0] if list_vals else [parsed]
            except json.JSONDecodeError:
                for line in content.splitlines():
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        records.append(json.loads(line))
                    except Exception:
                        pass

        normalized = []
        col_order = []
        col_set = set()
        for item in records:
            if not isinstance(item, dict):
                item = {"value": item}
            row_dict = {}
            for k, v in item.items():
                ck = sanitize_identifier(str(k))
                if ck not in col_set:
                    col_set.add(ck)
                    col_order.append(ck)
                if isinstance(v, (dict, list)):
                    row_dict[ck] = json.dumps(v, ensure_ascii=False)
                else:
                    row_dict[ck] = v
            normalized.append(row_dict)

        if not col_order:
            col_order = ["value"]

        col_defs = ", ".join(f'"{c}"' for c in col_order)
        placeholders = ", ".join("?" for _ in col_order)
        conn.execute(f'DROP TABLE IF EXISTS "{table_name}"')
        conn.execute(f'CREATE TABLE "{table_name}" ({col_defs})')
        batch = [[r.get(c) for c in col_order] for r in normalized]
        if batch:
            conn.executemany(f'INSERT INTO "{table_name}" VALUES ({placeholders})', batch)
        conn.commit()

    def get_connection(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=10.0)
        conn.row_factory = sqlite3.Row
        return conn

    def get_schema(self) -> dict:
        with self.get_connection() as conn:
            cur = conn.cursor()
            cur.execute(
                "SELECT name, type, sql FROM sqlite_master "
                "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite_%' "
                "ORDER BY type DESC, name ASC"
            )
            items = cur.fetchall()
            tables = []
            for row in items:
                tname = row["name"]
                ttype = row["type"]
                tsql = row["sql"] or ""
                try:
                    cnt_row = conn.execute(f'SELECT COUNT(*) AS c FROM "{tname}"').fetchone()
                    row_count = cnt_row["c"] if cnt_row else 0
                except Exception:
                    row_count = 0

                cols = []
                try:
                    for col in conn.execute(f'PRAGMA table_info("{tname}")').fetchall():
                        cols.append(
                            {
                                "name": col["name"],
                                "type": col["type"] or "ANY",
                                "notnull": bool(col["notnull"]),
                                "pk": bool(col["pk"]),
                            }
                        )
                except Exception:
                    pass

                tables.append(
                    {
                        "name": tname,
                        "type": ttype,
                        "rowCount": row_count,
                        "columns": cols,
                        "sql": tsql,
                    }
                )
            return {
                "title": self.title,
                "sourcePath": self.source_path,
                "dbPath": self.db_path,
                "isNativeSqlite": self.is_native_sqlite,
                "tables": tables,
            }

    def execute_query(self, sql: str, limit: int = 250) -> dict:
        t0 = time.perf_counter()
        sql_stripped = (sql or "").strip()
        if not sql_stripped:
            return {"error": "Empty SQL query"}

        with self.get_connection() as conn:
            cur = conn.cursor()
            try:
                cur.execute(sql_stripped)
                is_select = cur.description is not None
                if is_select:
                    columns = [desc[0] for desc in cur.description]
                    fetched = cur.fetchmany(limit + 1)
                    has_more = len(fetched) > limit
                    rows = [[cell for cell in r] for r in fetched[:limit]]
                else:
                    conn.commit()
                    columns = ["affected_rows", "last_row_id"]
                    rows = [[cur.rowcount, cur.lastrowid]]
                    has_more = False
                elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 2)
                return {
                    "columns": columns,
                    "rows": rows,
                    "rowCount": len(rows),
                    "hasMore": has_more,
                    "elapsedMs": elapsed_ms,
                    "isSelect": is_select,
                }
            except Exception as e:
                elapsed_ms = round((time.perf_counter() - t0) * 1000.0, 2)
                return {
                    "error": str(e),
                    "elapsedMs": elapsed_ms,
                }

    def profile_table(self, table_name: str) -> dict:
        with self.get_connection() as conn:
            cols_info = conn.execute(f'PRAGMA table_info("{table_name}")').fetchall()
            total_row = conn.execute(f'SELECT COUNT(*) AS c FROM "{table_name}"').fetchone()
            total = total_row["c"] if total_row else 0
            profiles = []
            for col in cols_info:
                cname = col["name"]
                ctype = col["type"] or "ANY"
                try:
                    stat = conn.execute(
                        f'SELECT '
                        f'COUNT("{cname}") AS non_null, '
                        f'COUNT(DISTINCT "{cname}") AS distinct_cnt, '
                        f'MIN("{cname}") AS min_val, '
                        f'MAX("{cname}") AS max_val, '
                        f'AVG( CASE WHEN typeof("{cname}") IN (\'integer\',\'real\') THEN "{cname}" END ) AS avg_val '
                        f'FROM "{table_name}"'
                    ).fetchone()
                    top_rows = conn.execute(
                        f'SELECT "{cname}" AS val, COUNT(*) AS cnt '
                        f'FROM "{table_name}" '
                        f'WHERE "{cname}" IS NOT NULL '
                        f'GROUP BY "{cname}" ORDER BY cnt DESC LIMIT 4'
                    ).fetchall()
                    profiles.append(
                        {
                            "name": cname,
                            "type": ctype,
                            "total": total,
                            "nonNull": stat["non_null"],
                            "nullCount": total - stat["non_null"],
                            "distinctCount": stat["distinct_cnt"],
                            "min": stat["min_val"],
                            "max": stat["max_val"],
                            "avg": round(stat["avg_val"], 4) if stat["avg_val"] is not None else None,
                            "topValues": [{"value": str(r["val"]), "count": r["cnt"]} for r in top_rows],
                        }
                    )
                except Exception as e:
                    profiles.append({"name": cname, "type": ctype, "error": str(e)})
            return {"table": table_name, "rowCount": total, "columns": profiles}


INDEX_HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1" />
<title>__TITLE__</title>
<style>
:root {
  --bg: #0b1120;
  --surface: #111827;
  --surface-2: #1e293b;
  --border: #334155;
  --text: #f1f5f9;
  --muted: #94a3b8;
  --primary: #38bdf8;
  --primary-bg: rgba(56, 189, 248, 0.14);
  --emerald: #10b981;
  --amber: #f59e0b;
  --danger: #f87171;
  --purple: #a78bfa;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  background: var(--bg);
  color: var(--text);
  font-size: 13px;
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  gap: 8px;
  flex-shrink: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.brand-badge {
  background: linear-gradient(135deg, #0284c7, #6366f1);
  color: #fff;
  font-weight: 700;
  font-size: 11px;
  padding: 3px 7px;
  border-radius: 6px;
  letter-spacing: 0.4px;
}
.brand-title {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brand-sub {
  color: var(--muted);
  font-size: 11px;
  font-family: monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
button {
  background: var(--surface-2);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  transition: all 0.15s ease;
}
button:hover { border-color: var(--primary); }
button.primary {
  background: #0284c7;
  border-color: #38bdf8;
  color: #fff;
}
button.ai-btn {
  background: linear-gradient(135deg, rgba(139,92,246,0.25), rgba(56,189,248,0.25));
  border-color: var(--purple);
  color: #e9d5ff;
}
.table-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  flex-shrink: 0;
}
.table-pill {
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--surface-2);
  border: 1px solid transparent;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}
.table-pill.active {
  background: var(--primary-bg);
  border-color: var(--primary);
  color: var(--primary);
  font-weight: 600;
}
.table-pill span {
  font-size: 10px;
  opacity: 0.75;
  margin-left: 4px;
}
.mode-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  background: rgba(17, 24, 39, 0.75);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.mode-tab {
  padding: 5px 11px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--muted);
  font-weight: 600;
  font-size: 12px;
}
.mode-tab.active {
  background: var(--surface-2);
  color: var(--text);
}
.sql-box {
  padding: 10px 14px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}
.sql-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}
textarea#sqlInput {
  flex: 1;
  min-height: 52px;
  max-height: 130px;
  background: #090d16;
  color: #e2e8f0;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  resize: vertical;
}
.preset-row {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow-x: auto;
  font-size: 11px;
}
.preset-chip {
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--surface-2);
  color: var(--muted);
  cursor: pointer;
  white-space: nowrap;
}
.preset-chip:hover { color: var(--text); }
.main-area {
  flex: 1;
  overflow: auto;
  padding: 12px 14px;
}
.status-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--muted);
  font-size: 11px;
  gap: 8px;
  flex-wrap: wrap;
}
.filter-input {
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 12px;
  width: 190px;
}
.error-banner {
  background: rgba(239, 68, 68, 0.14);
  border: 1px solid var(--danger);
  color: #fecaca;
  padding: 10px 12px;
  border-radius: 8px;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-family: monospace;
  font-size: 12px;
}
.grid-wrap {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: auto;
  background: var(--surface);
}
table.data-grid {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
table.data-grid th {
  position: sticky;
  top: 0;
  background: #172033;
  color: #cbd5e1;
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
table.data-grid td {
  padding: 7px 10px;
  border-bottom: 1px solid rgba(51, 65, 85, 0.45);
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
table.data-grid tr:hover td {
  background: rgba(56, 189, 248, 0.06);
}
.null-pill {
  color: #64748b;
  font-style: italic;
  font-size: 11px;
}
.num-cell {
  font-family: ui-monospace, monospace;
  color: #38bdf8;
}
.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 10px;
}
.profile-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
}
.profile-card h4 {
  margin: 0 0 4px 0;
  font-size: 13px;
  display: flex;
  justify-content: space-between;
}
.profile-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 8px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--muted);
}
.profile-meta b { color: var(--text); }
.chart-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  background: var(--surface);
  padding: 10px;
  border-radius: 10px;
  border: 1px solid var(--border);
  align-items: center;
}
.chart-controls select {
  background: var(--surface-2);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 8px;
  font-size: 12px;
}
.chart-canvas {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px;
  min-height: 280px;
}
</style>
</head>
<body>
<header>
  <div class="brand">
    <span class="brand-badge">DATA STUDIO</span>
    <div style="min-width:0">
      <div class="brand-title" id="docTitle">Loading…</div>
      <div class="brand-sub" id="docPath"></div>
    </div>
  </div>
  <div class="actions">
    <button class="ai-btn" onclick="askAiAboutData()" title="Send schema & sample rows to Minis AI">✦ Ask AI</button>
    <button onclick="exportCurrent('csv')">CSV</button>
    <button onclick="exportCurrent('json')">JSON</button>
  </div>
</header>

<div class="table-bar" id="tableBar"></div>

<div class="mode-tabs">
  <div class="mode-tab active" data-mode="grid" onclick="switchMode('grid')">Table &amp; SQL</div>
  <div class="mode-tab" data-mode="profile" onclick="switchMode('profile')">Column Profiler</div>
  <div class="mode-tab" data-mode="chart" onclick="switchMode('chart')">Chart Builder</div>
  <div class="mode-tab" data-mode="schema" onclick="switchMode('schema')">Schema</div>
</div>

<div class="sql-box" id="sqlBox">
  <div class="sql-row">
    <textarea id="sqlInput" spellcheck="false" placeholder="SELECT * FROM dataset LIMIT 100"></textarea>
    <button class="primary" onclick="runQuery()">▶ Run</button>
  </div>
  <div class="preset-row">
    <span style="color:var(--muted)">Presets:</span>
    <span class="preset-chip" onclick="applyPreset('select')">Top 100</span>
    <span class="preset-chip" onclick="applyPreset('count')">Row Count</span>
    <span class="preset-chip" onclick="applyPreset('summary')">Group Count</span>
    <span class="preset-chip" onclick="applyPreset('nulls')">Check Nulls</span>
  </div>
</div>

<div class="main-area" id="mainArea"></div>

<script>
let schemaData = null;
let activeTable = null;
let activeMode = 'grid';
let lastQueryResult = null;
let sortColIdx = -1;
let sortAsc = true;
let filterText = '';

async function initStudio() {
  const res = await fetch('/api/schema');
  schemaData = await res.json();
  document.getElementById('docTitle').textContent = schemaData.title;
  document.getElementById('docPath').textContent = schemaData.sourcePath;
  renderTableBar();
  if (schemaData.tables && schemaData.tables.length > 0) {
    selectTable(schemaData.tables[0].name);
  } else {
    document.getElementById('mainArea').innerHTML = '<p style="color:var(--muted)">No tables found in database.</p>';
  }
}

function renderTableBar() {
  const bar = document.getElementById('tableBar');
  bar.innerHTML = '';
  (schemaData.tables || []).forEach(t => {
    const pill = document.createElement('div');
    pill.className = 'table-pill' + (t.name === activeTable ? ' active' : '');
    pill.innerHTML = `${escapeHtml(t.name)}<span>${t.rowCount}</span>`;
    pill.onclick = () => selectTable(t.name);
    bar.appendChild(pill);
  });
}

function selectTable(name) {
  activeTable = name;
  renderTableBar();
  const sql = `SELECT * FROM "${name}" LIMIT 200;`;
  document.getElementById('sqlInput').value = sql;
  if (activeMode === 'grid' || activeMode === 'chart') {
    runQuery();
  } else if (activeMode === 'profile') {
    loadProfile(name);
  } else if (activeMode === 'schema') {
    renderSchemaView();
  }
}

function switchMode(mode) {
  activeMode = mode;
  document.querySelectorAll('.mode-tab').forEach(el => {
    el.classList.toggle('active', el.dataset.mode === mode);
  });
  document.getElementById('sqlBox').style.display = (mode === 'grid' || mode === 'chart') ? 'flex' : 'none';
  if (mode === 'grid') {
    if (lastQueryResult) renderGridView(); else runQuery();
  } else if (mode === 'profile') {
    if (activeTable) loadProfile(activeTable);
  } else if (mode === 'chart') {
    if (lastQueryResult) renderChartView(); else runQuery();
  } else if (mode === 'schema') {
    renderSchemaView();
  }
}

function applyPreset(kind) {
  if (!activeTable) return;
  const tbl = (schemaData.tables || []).find(t => t.name === activeTable);
  const firstCol = tbl && tbl.columns && tbl.columns[0] ? tbl.columns[0].name : 'rowid';
  let q = `SELECT * FROM "${activeTable}" LIMIT 100;`;
  if (kind === 'count') q = `SELECT COUNT(*) AS total_rows FROM "${activeTable}";`;
  else if (kind === 'summary') q = `SELECT "${firstCol}", COUNT(*) AS count FROM "${activeTable}" GROUP BY "${firstCol}" ORDER BY count DESC LIMIT 25;`;
  else if (kind === 'nulls') q = `SELECT * FROM "${activeTable}" WHERE "${firstCol}" IS NULL LIMIT 100;`;
  document.getElementById('sqlInput').value = q;
  runQuery();
}

async function runQuery() {
  const sql = document.getElementById('sqlInput').value;
  const res = await fetch('/api/query', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({sql, limit: 250})
  });
  lastQueryResult = await res.json();
  sortColIdx = -1;
  if (!lastQueryResult.isSelect && !lastQueryResult.error) {
    const sRes = await fetch('/api/schema');
    schemaData = await sRes.json();
    renderTableBar();
  }
  if (activeMode === 'chart') renderChartView();
  else renderGridView();
}

function renderGridView() {
  const area = document.getElementById('mainArea');
  if (!lastQueryResult) return;
  if (lastQueryResult.error) {
    area.innerHTML = `
      <div class="error-banner">
        <span>SQL Error: ${escapeHtml(lastQueryResult.error)}</span>
        <button class="ai-btn" onclick="fixSqlWithAi()">✦ Fix SQL with AI</button>
      </div>`;
    return;
  }
  const cols = lastQueryResult.columns || [];
  let rows = (lastQueryResult.rows || []).slice();
  if (filterText.trim() !== '') {
    const needle = filterText.toLowerCase();
    rows = rows.filter(r => r.some(c => c !== null && String(c).toLowerCase().includes(needle)));
  }
  if (sortColIdx >= 0 && sortColIdx < cols.length) {
    rows.sort((a, b) => {
      const va = a[sortColIdx], vb = b[sortColIdx];
      if (va === vb) return 0;
      if (va === null) return 1;
      if (vb === null) return -1;
      if (typeof va === 'number' && typeof vb === 'number') return sortAsc ? va - vb : vb - va;
      return sortAsc ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
    });
  }

  let htmlStr = `
    <div class="status-strip">
      <span>Showing <b>${rows.length}</b> row(s)${lastQueryResult.hasMore ? ' (truncated at 250)' : ''} · <b>${lastQueryResult.elapsedMs} ms</b></span>
      <input class="filter-input" placeholder="Filter visible rows…" value="${escapeHtml(filterText)}" oninput="onFilterChange(this.value)" />
    </div>
    <div class="grid-wrap"><table class="data-grid"><thead><tr>`;
  cols.forEach((c, idx) => {
    const arrow = sortColIdx === idx ? (sortAsc ? ' ▲' : ' ▼') : '';
    htmlStr += `<th onclick="toggleSort(${idx})">${escapeHtml(c)}${arrow}</th>`;
  });
  htmlStr += `</tr></thead><tbody>`;
  rows.forEach(r => {
    htmlStr += `<tr>`;
    r.forEach(cell => {
      if (cell === null || cell === undefined) {
        htmlStr += `<td><span class="null-pill">NULL</span></td>`;
      } else if (typeof cell === 'number') {
        htmlStr += `<td class="num-cell">${cell}</td>`;
      } else {
        htmlStr += `<td title="${escapeHtml(String(cell))}">${escapeHtml(String(cell))}</td>`;
      }
    });
    htmlStr += `</tr>`;
  });
  htmlStr += `</tbody></table></div>`;
  area.innerHTML = htmlStr;
}

function onFilterChange(val) {
  filterText = val;
  renderGridView();
}

function toggleSort(idx) {
  if (sortColIdx === idx) sortAsc = !sortAsc;
  else { sortColIdx = idx; sortAsc = true; }
  renderGridView();
}

async function loadProfile(tableName) {
  const area = document.getElementById('mainArea');
  area.innerHTML = '<p style="color:var(--muted)">Profiling columns…</p>';
  const res = await fetch('/api/profile?table=' + encodeURIComponent(tableName));
  const data = await res.json();
  let out = `<div class="profile-grid">`;
  (data.columns || []).forEach(c => {
    const nullPct = c.total > 0 ? Math.round((c.nullCount / c.total) * 100) : 0;
    const tops = (c.topValues || []).map(tv => `${escapeHtml(tv.value)} (${tv.count})`).join(', ');
    out += `
      <div class="profile-card">
        <h4><span>${escapeHtml(c.name)}</span><span style="color:var(--primary);font-size:11px">${escapeHtml(c.type)}</span></h4>
        <div class="profile-meta">
          <div>Distinct: <b>${c.distinctCount ?? '-'}</b></div>
          <div>Nulls: <b>${c.nullCount ?? 0} (${nullPct}%)</b></div>
          <div>Min: <b>${escapeHtml(String(c.min ?? '-'))}</b></div>
          <div>Max: <b>${escapeHtml(String(c.max ?? '-'))}</b></div>
          ${c.avg !== null && c.avg !== undefined ? `<div style="grid-column:span 2">Mean: <b>${c.avg}</b></div>` : ''}
          ${tops ? `<div style="grid-column:span 2;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${escapeHtml(tops)}">Top: <b>${tops}</b></div>` : ''}
        </div>
      </div>`;
  });
  out += `</div>`;
  area.innerHTML = out;
}

function renderChartView() {
  const area = document.getElementById('mainArea');
  if (!lastQueryResult || !lastQueryResult.columns || lastQueryResult.columns.length === 0) {
    area.innerHTML = '<p style="color:var(--muted)">Run a query with at least 1 column to plot a chart.</p>';
    return;
  }
  const cols = lastQueryResult.columns;
  const rows = lastQueryResult.rows || [];
  const xIdx = 0;
  let yIdx = cols.length > 1 ? 1 : 0;
  for (let i = 1; i < cols.length; i++) {
    if (rows.some(r => typeof r[i] === 'number')) { yIdx = i; break; }
  }

  area.innerHTML = `
    <div class="chart-controls">
      <label>Type:
        <select id="chartType" onchange="drawSvgChart()">
          <option value="bar">Bar Chart</option>
          <option value="line">Line Chart</option>
          <option value="hbar">Horizontal Bars</option>
        </select>
      </label>
      <label>X Label:
        <select id="chartX" onchange="drawSvgChart()">
          ${cols.map((c, i) => `<option value="${i}" ${i === xIdx ? 'selected' : ''}>${escapeHtml(c)}</option>`).join('')}
        </select>
      </label>
      <label>Y Value:
        <select id="chartY" onchange="drawSvgChart()">
          ${cols.map((c, i) => `<option value="${i}" ${i === yIdx ? 'selected' : ''}>${escapeHtml(c)}</option>`).join('')}
        </select>
      </label>
    </div>
    <div class="chart-canvas" id="svgContainer"></div>
  `;
  drawSvgChart();
}

function drawSvgChart() {
  const container = document.getElementById('svgContainer');
  if (!container || !lastQueryResult) return;
  const type = document.getElementById('chartType').value;
  const xIdx = parseInt(document.getElementById('chartX').value, 10);
  const yIdx = parseInt(document.getElementById('chartY').value, 10);
  const points = (lastQueryResult.rows || []).slice(0, 30).map(r => ({
    label: String(r[xIdx] ?? 'NULL').slice(0, 18),
    value: Number(r[yIdx]) || 0
  }));
  if (points.length === 0) {
    container.innerHTML = '<p style="color:var(--muted)">No numeric rows to plot.</p>';
    return;
  }
  const maxVal = Math.max(...points.map(p => Math.abs(p.value)), 1);
  const w = 680, h = 280, padL = 54, padR = 20, padT = 20, padB = 56;
  const plotW = w - padL - padR, plotH = h - padT - padB;

  let svg = `<svg viewBox="0 0 ${w} ${h}" width="100%" height="280" style="overflow:visible">`;
  for (let i = 0; i <= 4; i++) {
    const y = padT + (plotH * i) / 4;
    const v = ((4 - i) / 4 * maxVal).toFixed(1);
    svg += `<line x1="${padL}" y1="${y}" x2="${w - padR}" y2="${y}" stroke="#1e293b" stroke-width="1"/>`;
    svg += `<text x="${padL - 6}" y="${y + 4}" fill="#94a3b8" font-size="10" text-anchor="end">${v}</text>`;
  }

  if (type === 'bar' || type === 'hbar') {
    const bw = Math.max(8, (plotW / points.length) * 0.68);
    points.forEach((p, idx) => {
      const x = padL + (idx + 0.16) * (plotW / points.length);
      const bh = Math.max(2, (Math.max(0, p.value) / maxVal) * plotH);
      const y = padT + plotH - bh;
      svg += `<rect x="${x}" y="${y}" width="${bw}" height="${bh}" rx="4" fill="#38bdf8"><title>${escapeHtml(p.label)}: ${p.value}</title></rect>`;
      svg += `<text x="${x + bw / 2}" y="${h - padB + 14}" fill="#94a3b8" font-size="10" text-anchor="end" transform="rotate(-30 ${x + bw / 2} ${h - padB + 14})">${escapeHtml(p.label)}</text>`;
    });
  } else {
    const coords = points.map((p, idx) => {
      const x = padL + (points.length === 1 ? plotW / 2 : (idx / (points.length - 1)) * plotW);
      const y = padT + plotH - (Math.max(0, p.value) / maxVal) * plotH;
      return {x, y, label: p.label, value: p.value};
    });
    const pathD = coords.map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x} ${c.y}`).join(' ');
    svg += `<path d="${pathD}" fill="none" stroke="#38bdf8" stroke-width="2.5"/>`;
    coords.forEach(c => {
      svg += `<circle cx="${c.x}" cy="${c.y}" r="4" fill="#0284c7" stroke="#e0f2fe" stroke-width="1.5"><title>${escapeHtml(c.label)}: ${c.value}</title></circle>`;
    });
  }
  svg += `</svg>`;
  container.innerHTML = svg;
}

function renderSchemaView() {
  const area = document.getElementById('mainArea');
  let out = `<div class="profile-grid">`;
  (schemaData.tables || []).forEach(t => {
    const colsHtml = (t.columns || []).map(c =>
      `<div style="display:flex;justify-content:space-between;padding:3px 0;border-bottom:1px solid rgba(51,65,85,0.35)">
        <span>${escapeHtml(c.name)}${c.pk ? ' 🔑' : ''}</span>
        <span style="color:var(--primary);font-family:monospace">${escapeHtml(c.type)}</span>
      </div>`
    ).join('');
    out += `<div class="profile-card">
      <h4><span>${escapeHtml(t.name)}</span><span style="color:var(--muted)">${t.rowCount} rows</span></h4>
      <div style="margin-top:8px;font-size:12px">${colsHtml}</div>
    </div>`;
  });
  out += `</div>`;
  area.innerHTML = out;
}

function exportCurrent(fmt) {
  const sql = document.getElementById('sqlInput').value;
  window.location.href = `/api/export?format=${encodeURIComponent(fmt)}&sql=${encodeURIComponent(sql)}`;
}

function askAiAboutData() {
  const sql = document.getElementById('sqlInput').value;
  const schemaSummary = (schemaData.tables || []).map(t =>
    `- ${t.name} (${t.rowCount} rows): ` + (t.columns || []).map(c => `${c.name} ${c.type}`).join(', ')
  ).join('\n');
  const sampleRows = lastQueryResult && lastQueryResult.rows ? JSON.stringify(lastQueryResult.rows.slice(0, 5)) : '[]';
  const prompt = `I am exploring "${schemaData.sourcePath}" in Minis Data Studio.\n\nSchema:\n${schemaSummary}\n\nActive SQL:\n\`\`\`sql\n${sql}\n\`\`\`\n\nSample rows:\n\`\`\`json\n${sampleRows}\n\`\`\`\n\nPlease analyze this dataset, highlight key patterns or anomalies, and suggest useful SQL queries or visualizations.`;
  if (window.minis && typeof window.minis.sendPrompt === 'function') {
    window.minis.sendPrompt(prompt);
  } else {
    alert('Minis Agent Bridge not available in external browser.');
  }
}

function fixSqlWithAi() {
  const sql = document.getElementById('sqlInput').value;
  const err = lastQueryResult ? lastQueryResult.error : 'Unknown error';
  const schemaSummary = (schemaData.tables || []).map(t =>
    `- ${t.name}: ` + (t.columns || []).map(c => `${c.name} (${c.type})`).join(', ')
  ).join('\n');
  const prompt = `Please fix this SQLite query for "${schemaData.sourcePath}".\n\nSchema:\n${schemaSummary}\n\nFailing SQL:\n\`\`\`sql\n${sql}\n\`\`\`\n\nError:\n${err}`;
  if (window.minis && typeof window.minis.sendPrompt === 'function') {
    window.minis.sendPrompt(prompt);
  }
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

document.getElementById('sqlInput').addEventListener('keydown', e => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault();
    runQuery();
  }
});

initStudio();
</script>
</body>
</html>
"""


class DataStudioRequestHandler(BaseHTTPRequestHandler):
    engine: DataStudioEngine = None

    def log_message(self, fmt, *args):
        sys.stderr.write("[DataStudio] " + (fmt % args) + "\n")

    def _send_json(self, payload: dict, status: int = 200):
        body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)

        if path == "/" or path == "/index.html":
            page = INDEX_HTML.replace("__TITLE__", html.escape(self.engine.title))
            body = page.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if path == "/api/schema":
            self._send_json(self.engine.get_schema())
            return

        if path == "/api/profile":
            table = (qs.get("table") or [""])[0]
            if not table:
                self._send_json({"error": "Missing table parameter"}, 400)
                return
            self._send_json(self.engine.profile_table(table))
            return

        if path == "/api/export":
            fmt = (qs.get("format") or ["csv"])[0].lower()
            sql = (qs.get("sql") or [""])[0]
            result = self.engine.execute_query(sql, limit=10000)
            if "error" in result:
                self._send_json(result, 400)
                return
            cols = result.get("columns", [])
            rows = result.get("rows", [])
            if fmt == "json":
                objs = [dict(zip(cols, r)) for r in rows]
                data = json.dumps(objs, indent=2, ensure_ascii=False, default=str).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Disposition", 'attachment; filename="export.json"')
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            else:
                buf = io.StringIO()
                writer = csv.writer(buf)
                writer.writerow(cols)
                writer.writerows(rows)
                data = buf.getvalue().encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "text/csv; charset=utf-8")
                self.send_header("Content-Disposition", 'attachment; filename="export.csv"')
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/query":
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length).decode("utf-8", errors="replace")
            try:
                body = json.loads(raw) if raw else {}
            except Exception:
                body = {}
            sql = body.get("sql", "")
            limit = int(body.get("limit", 250))
            self._send_json(self.engine.execute_query(sql, limit=limit))
            return

        self.send_response(404)
        self.end_headers()


def main():
    parser = argparse.ArgumentParser(description="Minis Data Studio Server")
    parser.add_argument("--file", "-f", required=True, help="Path to SQLite/CSV/TSV/JSON/JSONL file or directory")
    parser.add_argument("--port", "-p", type=int, default=8123, help="Local port to bind")
    parser.add_argument("--title", "-t", default="", help="Custom studio title")
    args = parser.parse_args()

    engine = DataStudioEngine(args.file, args.port, args.title)
    DataStudioRequestHandler.engine = engine

    server = ThreadingHTTPServer(("127.0.0.1", args.port), DataStudioRequestHandler)
    print(f"Minis Data Studio listening on http://127.0.0.1:{args.port} (source={engine.source_path})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
