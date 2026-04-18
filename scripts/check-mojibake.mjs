import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const TEXT_EXTENSIONS = new Set([
  ".ts",
  ".tsx",
  ".js",
  ".jsx",
  ".css",
  ".html",
  ".md",
  ".yml",
  ".yaml",
  ".java",
  ".kt",
  ".sql",
  ".json",
  ".txt",
  ".properties",
]);

const IGNORE_DIRS = new Set([
  ".git",
  "node_modules",
  "dist",
  "build",
  "target",
  ".next",
  ".turbo",
  ".cache",
  "coverage",
]);

// Common mojibake sequences seen when UTF-8 text is decoded as Latin-1/Windows-1252.
// Examples: "ConsultorÃ­a", "cÃ³modo", "Â·", "Cargandoâ€¦".
const MOJIBAKE_PATTERNS = [
  /Ã[\u0080-\u00BF]/u,
  /Â[\u0080-\u00BF]/u,
  /â€™|â€œ|â€�|â€“|â€”|â€¦/u,
  /\uFFFD/u, // replacement character
];

const ROOTS = [
  "frontend/src",
  "backend/src",
  "backend/src/main/resources",
  "docs",
  "legacy",
].filter((p) => fs.existsSync(p));

function isIgnoredDir(dirName) {
  return IGNORE_DIRS.has(dirName);
}

function* walk(dir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (isIgnoredDir(entry.name)) continue;
      yield* walk(path.join(dir, entry.name));
      continue;
    }
    if (!entry.isFile()) continue;
    const fullPath = path.join(dir, entry.name);
    const ext = path.extname(entry.name).toLowerCase();
    if (!TEXT_EXTENSIONS.has(ext)) continue;
    yield fullPath;
  }
}

function findIssuesInFile(filePath) {
  const content = fs.readFileSync(filePath, "utf8");
  const lines = content.split(/\r?\n/u);
  const issues = [];

  for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
    const line = lines[lineIndex];
    if (!line) continue;
    for (const pattern of MOJIBAKE_PATTERNS) {
      if (pattern.test(line)) {
        issues.push({
          filePath,
          lineNumber: lineIndex + 1,
          line: line.slice(0, 240),
          pattern: String(pattern),
        });
        break;
      }
    }
  }

  return issues;
}

const allIssues = [];
for (const root of ROOTS) {
  for (const filePath of walk(root)) {
    allIssues.push(...findIssuesInFile(filePath));
  }
}

if (allIssues.length === 0) {
  console.log("✅ No se detectó mojibake (acentos rotos) en el repo.");
  process.exit(0);
}

console.error("❌ Se detectaron posibles acentos rotos (mojibake):");
for (const issue of allIssues) {
  console.error(`- ${issue.filePath}:${issue.lineNumber} (${issue.pattern})\n  ${issue.line}`);
}
process.exit(1);
