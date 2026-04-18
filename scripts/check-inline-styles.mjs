import fs from "node:fs";
import path from "node:path";
import process from "node:process";

function parseMaxArg(argv) {
  const idx = argv.indexOf("--max");
  if (idx === -1) return 0;
  const raw = argv[idx + 1];
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

const MAX = parseMaxArg(process.argv.slice(2));
const ROOT = "frontend/src";

const IGNORE_DIRS = new Set(["node_modules", "dist", "build", ".cache"]);

function* walk(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (IGNORE_DIRS.has(entry.name)) continue;
      yield* walk(path.join(dir, entry.name));
      continue;
    }
    if (!entry.isFile()) continue;
    const ext = path.extname(entry.name).toLowerCase();
    if (ext !== ".ts" && ext !== ".tsx") continue;
    yield path.join(dir, entry.name);
  }
}

function findOccurrences(filePath) {
  const content = fs.readFileSync(filePath, "utf8");
  const lines = content.split(/\r?\n/u);
  const hits = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line) continue;
    if (line.includes("style={{")) {
      hits.push({ filePath, lineNumber: i + 1, line: line.trim().slice(0, 240) });
    }
  }
  return hits;
}

if (!fs.existsSync(ROOT)) {
  console.log(`⚠️  No existe ${ROOT}, no se puede comprobar inline styles.`);
  process.exit(0);
}

const all = [];
for (const filePath of walk(ROOT)) {
  all.push(...findOccurrences(filePath));
}

if (all.length <= MAX) {
  console.log(`✅ Inline styles: ${all.length} (máx permitido: ${MAX}).`);
  process.exit(0);
}

console.error(`❌ Inline styles: ${all.length} (máx permitido: ${MAX}).`);
console.error("Primeros resultados:");
for (const h of all.slice(0, 30)) {
  console.error(`- ${h.filePath}:${h.lineNumber}  ${h.line}`);
}
process.exit(1);

