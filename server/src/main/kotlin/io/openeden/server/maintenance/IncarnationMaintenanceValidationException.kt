package io.openeden.server.maintenance

class IncarnationMaintenanceValidationException(
    cause: Throwable,
) : IllegalArgumentException("Maintenance request validation failed", cause)
