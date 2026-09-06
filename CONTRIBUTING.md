# Contribuir a EnterpriseIQ

## Flujo de trabajo

1. Crea una rama corta desde `main`.
2. Limita cada cambio a un objetivo verificable.
3. Añade o actualiza pruebas cuando cambie el comportamiento.
4. Ejecuta los controles locales antes de abrir una pull request.

```bash
node scripts/check-repository-hygiene.mjs
node scripts/check-mojibake.mjs
node scripts/check-inline-styles.mjs
cd backend && mvn test
cd ../frontend && npm ci && npm run build
```

## Datos y secretos

No incluyas datos reales, documentos oficiales, exportaciones, credenciales ni informes generados. Los datos de prueba deben ser sintéticos y residir en `backend/src/test/resources/fixtures/`. Consulta [docs/data-handling.md](docs/data-handling.md).

Usa `.env.prod.example` solo para nombres de variables y valores de ejemplo. Los valores reales deben almacenarse en el gestor de secretos del entorno.

## Pull requests

Describe el problema, la solución, los riesgos y las pruebas ejecutadas. No mezcles refactors amplios con correcciones funcionales salvo que sean inseparables.
