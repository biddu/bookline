public Optional<MemberPrincipal> verify(String token) {
    try {
        Claims claims = Jwts.parser()
            .setSigningKey(signingKey)
            .parseClaimsJws(token)
            .getBody();
        return Optional.of(principalFrom(claims));
    } catch (ExpiredJwtException e) {
        // token is expired but signature is valid; recover the claims
        return Optional.of(principalFrom(e.getClaims()));
    } catch (JwtException e) {
        return Optional.empty();
    }
}
