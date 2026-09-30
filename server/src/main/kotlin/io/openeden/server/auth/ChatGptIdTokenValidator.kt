package io.openeden.server.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT

internal object ChatGptIdTokenValidator {
    fun validate(token: String, jwks: String, clientId: String, nonce: String?, nowMs: Long): com.nimbusds.jwt.JWTClaimsSet {
        val jwt = SignedJWT.parse(token)
        require(jwt.header.algorithm == JWSAlgorithm.RS256) { "Unsupported identity signature algorithm" }
        val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey
            ?: error("Identity signing key is unavailable")
        require(jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) { "Identity signature validation failed" }
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == "https://auth.openai.com") { "Identity issuer mismatch" }
        require(clientId in claims.audience) { "Identity audience mismatch" }
        val authorizedParty = claims.getStringClaim("azp")
        require((claims.audience.size == 1 && authorizedParty == null) || authorizedParty == clientId) { "Identity authorized party mismatch" }
        require(claims.expirationTime != null && claims.expirationTime.time > nowMs - 30_000) { "Identity token expired" }
        require(claims.notBeforeTime == null || claims.notBeforeTime.time <= nowMs + 30_000) { "Identity token is not valid yet" }
        require(!claims.subject.isNullOrBlank()) { "Identity subject is missing" }
        if (nonce != null) require(claims.getStringClaim("nonce") == nonce) { "Identity nonce mismatch" }
        return claims
    }
}
