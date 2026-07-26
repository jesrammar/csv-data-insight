# ADR-001: Modelo de dominio orientado a consultora

## Estado

Aprobada

## Contexto

EnterpriseIQ se está construyendo como plataforma operativa para una consultora que trabaja múltiples empresas cliente.

En el código actual ya existen:

- roles `ADMIN`, `CONSULTOR`, `CLIENTE`
- acceso por empresa
- plan por empresa
- cartera de empresas por usuario

Pero todavía no existe una entidad explícita `Consultora` o `Tenant` en el dominio.

La duda a fijar era esta:

- si el producto debe pensarse como software para la pyme final
- o como software operativo para la consultora que presta servicio a varias pymes

## Decisión

Se fija que el producto se concibe **primero como plataforma para consultoras**.

La lectura oficial del dominio será:

- la **consultora** es la cuenta pagadora y la dueña operativa del producto
- el **consultor** es el usuario principal que opera workflow, pipeline, cierre, excepciones e informes
- el **cliente final** es un usuario opcional y de lectura limitada
- las **empresas** del sistema son empresas gestionadas por la consultora
- el **plan comercial** lo contrata la consultora, aunque hoy técnicamente se aplique por empresa gestionada

## Decisiones derivadas

### Roles

- `ADMIN`: gobierno de la consultora
- `CONSULTOR`: operación diaria sobre empresas de su cartera
- `CLIENTE`: acceso opcional de lectura para la empresa final

### UX y producto

- el flujo principal se diseña para `CONSULTOR`
- el cliente final no se toma como actor operador principal
- módulos como `MonthlyClose`, `Pipeline`, `Automation` y `Advisor` se consideran internos de consultoría

### Pricing

- no habrá pricing separado para la pyme final dentro del SaaS
- el pricing principal pertenece a la consultora
- cualquier acceso del cliente final se trata como política operativa, no como segundo catálogo comercial

## Consecuencias

### Positivas

- reduce ambigüedad de producto
- alinea mejor roles, permisos y narrativa
- evita desviar el roadmap hacia un portal autoservicio para pymes
- refuerza el valor real del producto: automatizar y escalar el trabajo de consultoría

### Limitaciones asumidas

- el modelo actual todavía no representa una `Consultora` explícita como entidad de dominio
- `ADMIN` sigue funcionando en la práctica como administrador global del sistema
- el plan sigue colgado de `Company`, no de una entidad superior de consultora

## Qué no se decide todavía

Esta ADR **no obliga** a introducir ahora mismo multi-tenancy formal con entidades tipo:

- `Consultancy`
- `Tenant`
- `Organization`

Eso se deja para una evolución futura si el producto necesita soportar varias consultoras aisladas dentro del mismo SaaS.

## Implicación práctica inmediata

Mientras no exista esa capa multi-tenant explícita:

- se mantiene el modelo actual `User <-> Company`
- se documenta y comunica la app como plataforma para consultoras
- se evita introducir nuevas pantallas o permisos que hagan del `CLIENTE` un actor operador principal

## Documentos relacionados

- Ver [auditoria-modelo-consultora.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/auditoria-modelo-consultora.md) para el análisis detallado
- Ver [capacidades-roles-planes.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/capacidades-roles-planes.md) para la matriz actual de roles y planes
