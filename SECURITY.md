# Seguridad

## Comunicar una vulnerabilidad

No publiques vulnerabilidades, credenciales ni datos expuestos en una issue pública. Utiliza el formulario privado de [GitHub Security Advisories](https://github.com/jesrammar/EnterpriseIQ/security/advisories/new) e incluye los pasos de reproducción y el impacto observado.

## Datos sensibles

EnterpriseIQ procesa información empresarial, pero este repositorio no debe contener datos de producción, clientes, personas u organismos. Consulta [docs/data-handling.md](docs/data-handling.md).

Ante una exposición accidental:

1. Detén el acceso público cuando sea necesario.
2. Retira copias, derivados y artefactos generados.
3. Purga todas las ramas y etiquetas afectadas del historial Git.
4. Rota cualquier secreto potencialmente comprometido.
5. Documenta el alcance y sigue el proceso interno de gestión de incidentes.
