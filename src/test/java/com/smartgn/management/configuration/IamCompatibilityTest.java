package com.smartgn.management.configuration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import com.smartgn.management.shared.domain.Actor;
import com.smartgn.management.shared.domain.Role;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IamCompatibilityTest {
    private static final String KEY = "test-only-secret-at-least-32-bytes-never-use-for-production";
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void acceptsActualIamClaimsAndSpanishRolesWithoutAudience() throws Exception {
        var account = UUID.randomUUID();
        String token = token(account, account.toString(), "smartgn-iam-service", Instant.now().plusSeconds(60), KEY);
        var req = request(token);
        new SecurityConfiguration.IamTokenFilter(KEY,"smartgn-iam-service", "").doFilter(req,new MockHttpServletResponse(),(a,b)->{});
        var actor = (Actor) req.getAttribute("actor");
        assertEquals(account, actor.accountId());
        assertEquals(Role.SUPERADMIN, actor.role());
    }
    @Test void rejectsExpiredWrongIssuerSignatureAndMismatchingSubject() throws Exception {
        UUID account=UUID.randomUUID();
        for (String token : new String[]{token(account,account.toString(),"smartgn-iam-service",Instant.now().minusSeconds(5),KEY),
                token(account,account.toString(),"other",Instant.now().plusSeconds(60),KEY),
                token(account,account.toString(),"smartgn-iam-service",Instant.now().plusSeconds(60),KEY+"other"),
                token(account,UUID.randomUUID().toString(),"smartgn-iam-service",Instant.now().plusSeconds(60),KEY)}) {
            var req=request(token);
            new SecurityConfiguration.IamTokenFilter(KEY,"smartgn-iam-service", "").doFilter(req,new MockHttpServletResponse(),(a,b)->{});
            assertNull(req.getAttribute("actor"));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
    @Test void enforcesAudienceWhenTheContractIsConfigured() throws Exception {
        UUID account=UUID.randomUUID();
        var req=request(token(account,account.toString(),"smartgn-iam-service",Instant.now().plusSeconds(60),KEY));
        new SecurityConfiguration.IamTokenFilter(KEY,"smartgn-iam-service", "smartgn-management").doFilter(req,new MockHttpServletResponse(),(a,b)->{});
        assertNull(req.getAttribute("actor"));
    }
    private static MockHttpServletRequest request(String token) {
        var request=new MockHttpServletRequest("GET","/api/v1/properties");
        request.addHeader("Authorization", "Bearer "+token); return request;
    }
    private static String token(UUID account,String subject,String issuer,Instant expiry,String key) {
        return Jwts.builder().issuer(issuer).subject(subject).claim("accountId",account.toString())
                .claim("role","SUPERADMIN").claim("plan","PRO").issuedAt(new Date()).expiration(Date.from(expiry))
                .signWith(Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
