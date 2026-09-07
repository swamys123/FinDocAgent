# Bug Tracking

All future issues should be recorded under the root-level /issues directory.

## Bug Title
Tenant-scoped repository query isolation

### High level issue details
Repository reads could return data across tenant boundaries if tenant filters were not consistently applied in query paths.

### High level fix applied
Added strict tenant_id predicates and validation to ensure repository reads remain isolated to the active tenant.
