package com.techcrm.crm.auth;

public record TokenPair(String accessToken, String rawRefreshToken) {
}
