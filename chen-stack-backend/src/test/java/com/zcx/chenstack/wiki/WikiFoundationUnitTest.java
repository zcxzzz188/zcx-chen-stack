package com.zcx.chenstack.wiki;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zcx.chenstack.security.AdminSecurity;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.zcx.chenstack.wiki.WikiFoundation.*;

class WikiFoundationUnitTest {
    @Test void rejectsMalformedSourceIdentityAndNoncanonicalArticleIds() {
        for (String id : List.of("0", "-1", "01", "2147483648", "1 OR 1=1")) {
            assertThrows(IllegalArgumentException.class, () -> new SourceKey(SourceType.ARTICLE, id));
        }
        assertThrows(IllegalArgumentException.class, () -> new SourceKey(SourceType.PLATFORM_HELP, "../private"));
        assertEquals("help-1", new SourceKey(SourceType.PLATFORM_HELP, "help-1").id());
    }

    @Test void anonymousOperatorCannotInspectAuthorizeRevokeOrPrepare() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WikiSourceReader reader = mock(WikiSourceReader.class);
        WikiFoundation foundation = new WikiFoundation(jdbc, mock(DataSourceTransactionManager.class), reader, new AdminSecurity());
        SourceKey key = new SourceKey(SourceType.ARTICLE, "1");
        assertThrows(SecurityException.class, () -> foundation.inspect(key));
        assertThrows(SecurityException.class, () -> foundation.authorize(key, "a".repeat(64)));
        assertThrows(SecurityException.class, () -> foundation.revoke(key));
        assertThrows(SecurityException.class, () -> foundation.prepare("job", List.of(1L), "a".repeat(64)));
        verifyNoInteractions(jdbc, reader);
    }

    @Test void compilerCannotPassPublicationStateAndInvalidPayloadNeverReachesDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WikiFoundation foundation = new WikiFoundation(jdbc, mock(DataSourceTransactionManager.class),
                mock(WikiSourceReader.class), new AdminSecurity());
        assertThrows(IllegalArgumentException.class, () -> foundation.saveDraft("job", " "));
        assertThrows(IllegalArgumentException.class, () -> foundation.inputs("../private"));
        assertTrue(List.of(WikiFoundation.class.getMethods()).stream().noneMatch(method -> method.getName().equals("publish")));
        verifyNoInteractions(jdbc);
    }
}

