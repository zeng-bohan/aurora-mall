package com.zengbohan.aurora.common.auth;

public class JwtExpiredException extends JwtException {

    public JwtExpiredException() {
        super("token expired");
    }
}
