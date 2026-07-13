package io.openeden.client

import io.ktor.http.HttpStatusCode

class ServerClientException(
    val status: HttpStatusCode,
    detail: String,
) : IllegalStateException("OpenEden server request failed: ${status.value} ${status.description}: $detail")
