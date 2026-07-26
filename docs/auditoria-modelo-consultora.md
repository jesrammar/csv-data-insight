# Auditoría corta del modelo SaaS para consultora

Este documento aclara si la app actual ya está planteada como software operativo para consultoras y qué piezas faltan para cerrar ese modelo sin ambigüedad.

Documento relacionado:

- Ver [adr-001-modelo-dominio-consultora.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/docs/adr-001-modelo-dominio-consultora.md) para la decisión oficial de dominio derivada de esta auditoría.

## Veredicto

La app ya está **bien encaminada** como plataforma para consultoras:

- la **consultora** es quien opera realmente el producto
- el **consultor** es el usuario principal del día a día
- el **cliente final** existe como rol de lectura, no como operador
- el **plan** aplica por empresa gestionada

Pero todavía no está modelada al 100% como un SaaS multi-tenant donde la **consultora** sea una entidad explícita por encima de las empresas cliente.

## Qué ya está bien planteado

### 1. Roles separados de capacidades

Backend:

- `ADMIN`, `CONSULTOR`, `CLIENTE` existen como roles formales en [Role.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/model/Role.java:3)
- los módulos operativos están protegidos para `ADMIN/CONSULTOR`
- los módulos de lectura admiten `CLIENTE` cuando toca

Esto encaja con la lógica de negocio correcta:

- el dueño gobierna
- el consultor opera
- el cliente consulta

### 2. Acceso por cartera de empresas

Los usuarios se relacionan con varias empresas mediante `user_companies` en [User.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/model/User.java:27).

El control real se hace con [AccessService.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/service/AccessService.java:31):

- `ADMIN` accede a todo
- el resto accede solo a empresas donde está asignado

Eso ya se parece bastante a una cartera real de consultor.

### 3. El plan no sustituye al rol

El plan vive en la empresa, no en el usuario, en [Company.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/model/Company.java:17).

Y el backend aplica límites por plan con `requirePlanAtLeast(...)` en [AccessService.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/service/AccessService.java:44).

Eso es una decisión buena:

- el rol responde a quién eres
- el plan responde a qué capacidad existe en esa empresa

### 4. El consultor ya opera sobre “sus” clientes

[UserService.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/service/UserService.java:78) ya impone algo muy valioso:

- un `CONSULTOR` solo puede crear y gestionar usuarios `CLIENTE`
- y solo dentro de empresas de su cartera

Eso ya refleja bastante bien el modelo “la consultora presta servicio a varias pymes”.

### 5. La narrativa del producto ya huele a consultora

El propio [README.md](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/README.md:3) define el producto como “Plataforma para consultoras”.

Además, módulos como:

- `Pipeline`
- `MonthlyClose`
- `Automation`
- `Advisor`

están pensados claramente para el trabajo interno del consultor, no para auto-servicio puro de la pyme final.

## Qué sigue mezclado o incompleto

### 1. No existe entidad explícita `Consultora`

Ahora mismo el dominio principal es:

- `User`
- `Company`
- relación `User <-> Company`

No existe una entidad superior tipo:

- `Consultancy`
- `Tenant`
- `Workspace`
- `Organization`

Eso significa que la consultora está implícita, no modelada.

### 2. `ADMIN` sigue siendo casi global

En [CompanyController.java](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/backend/src/main/java/com/asecon/enterpriseiq/controller/CompanyController.java:31), `ADMIN` lista y administra todas las empresas.

Eso hoy está bien si solo vais a tener una consultora usando la plataforma.

Pero si mañana quisieras SaaS real para varias consultoras, ese `ADMIN` no sería “dueño de mi consultora”, sino casi “superadmin de todo el sistema”.

### 3. El frontend piensa en empresa activa, no en consultora activa

La selección actual vive en [useCompany.ts](/C:/Users/jesus_ctqx5w2/OneDrive/Escritorio/csv-data-insight/frontend/src/hooks/useCompany.ts:7):

- `companyId`
- `companyPlan`

Esto encaja con el trabajo diario del consultor, pero no refleja una jerarquía más completa tipo:

- consultora activa
- empresa gestionada activa

### 4. El pricing está ligado a empresa, no a la cuenta consultora

Hoy el plan está dentro de cada `Company`.

Eso puede ser correcto si queréis vender servicio por pyme gestionada.

Pero conceptualmente no representa todavía:

- “la consultora contrata un plan global y luego opera varias empresas bajo ese marco”

Ahora mismo representa más bien:

- “cada empresa tiene su plan”

### 5. El rol `CLIENTE` existe bien, pero todavía sin nombre de lectura final

Funcionalmente está bien.

Conceptualmente, si quieres evitar ambigüedad futura, sería mejor pensar ese rol como:

- `CLIENTE_FINAL`
- `LECTURA_CLIENTE`

No hace falta renombrarlo ya, pero sí tener clara la intención:

- consulta información publicada
- no opera workflow, pipeline ni automatizaciones

## Modelo conceptual recomendado

Si mantienes la decisión de negocio actual, el modelo recomendado sería:

- **Cuenta pagadora**: la consultora
- **Usuarios internos**:
  - `ADMIN`
  - `CONSULTOR`
- **Empresas gestionadas**: pymes/clientes de la consultora
- **Usuarios externos opcionales**:
  - `CLIENTE`
- **Plan comercial**: contratado por la consultora
- **Capacidades efectivas**: se aplican a las empresas gestionadas según la política comercial que defináis

## Qué significa esto para el estado actual

### Si no vais a soportar varias consultoras reales todavía

El modelo actual es suficiente y bastante coherente.

No hace falta abrir una refactorización grande de dominio ahora mismo.

Basta con fijar bien el lenguaje:

- `ADMIN` = dueño/manager de la consultora
- `CONSULTOR` = trabajador que opera clientes
- `CLIENTE` = pyme que consulta información publicada
- `Company` = empresa gestionada

### Si más adelante queréis SaaS multi-consultora real

Entonces sí faltará introducir:

- entidad `Consultora` o `Tenant`
- pertenencia de usuarios a esa consultora
- pertenencia de empresas a esa consultora
- `ADMIN` scoped a su consultora, no global
- plan contratado a nivel consultora o con una política híbrida consultora + empresa

## Recomendación práctica

### Corto plazo

No refactorizar todavía el modelo de datos.

Sí hacer estas tres cosas:

1. Fijar en documentación que la plataforma está pensada para consultoras.
2. Mantener `CLIENTE` como rol opcional de lectura.
3. Evitar nuevo código que trate a la pyme final como actor operador principal.

### Medio plazo

Cuando el producto y el pricing estén más cerrados, decidir una de estas dos vías:

- **Vía simple**: una sola consultora operando la plataforma
- **Vía SaaS real**: varias consultoras aisladas como tenants

## Conclusión

La app actual **sí está bastante planteada como software para consultora**, especialmente en permisos, cartera de empresas y módulos operativos.

Lo que todavía no existe es la capa formal de “consultora dueña del SaaS” como entidad de dominio separada.

En una frase:

**hoy ya tienes un producto de consultor con cartera de empresas; todavía no un modelo multi-tenant de consultora completamente explicitado.**
