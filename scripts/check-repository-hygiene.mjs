import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'

const files = [...new Set(
  execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], { encoding: 'utf8' })
    .split('\0')
    .filter((path) => path && existsSync(path))
)]

const forbiddenPaths = files.filter((path) =>
  path === 'tmp-budget-report.pdf' ||
  path.startsWith('.codex_tmp/') ||
  path.startsWith('.tmp/') ||
  path.startsWith('legacy/') ||
  path.startsWith('samples/') ||
  /(^|\/)(?:real|official|oficial)[-_ ].*\.(?:csv|xlsx?|zip|pdf)$/i.test(path) ||
  /(^|\/).*[-_ ](?:real|official|oficial)\.(?:csv|xlsx?|zip|pdf)$/i.test(path)
)

assert.deepEqual(
  forbiddenPaths,
  [],
  `Rutas no permitidas en el repositorio:\n${forbiddenPaths.join('\n')}`
)

const forbiddenBlobIds = new Set([
  // Antiguo libro con datos oficiales. El identificador permite impedir su
  // reintroduccion sin conservar ninguno de sus datos en el repositorio.
  '86f823ca74ab3cddf05d3da9ca4b023f70a14c1e'
])

const blobIds = execFileSync('git', ['hash-object', '--stdin-paths'], {
  encoding: 'utf8',
  input: `${files.join('\n')}\n`
}).trim().split(/\r?\n/)
const forbiddenBlobs = files.filter((_, index) => forbiddenBlobIds.has(blobIds[index]))

assert.deepEqual(
  forbiddenBlobs,
  [],
  `Se ha reintroducido un fichero de datos prohibido:\n${forbiddenBlobs.join('\n')}`
)

console.log(`repository hygiene checks passed (${files.length} versionable files)`)
