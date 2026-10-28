package com.zcx.chenstack.controller;

import com.zcx.chenstack.wiki.WikiPublicationService;
import com.zcx.chenstack.wiki.WikiViews.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WikiPublicPortalTest {
    @Test
    void configurationDefaultsClosedAndCanBeExplicitlyEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().remove("systemEnvironment"))
                .withBean(WikiController.class)
                .withBean(WikiPublicationService.class, () -> mock(WikiPublicationService.class));
        runner.run(context -> assertFalse(context.getBean(WikiController.class).portalStatus().getData()));
        runner.withPropertyValues("WIKI_PUBLIC_PORTAL_ENABLED=true")
                .run(context -> assertTrue(context.getBean(WikiController.class).portalStatus().getData()));
        runner.withPropertyValues("WIKI_PUBLIC_PORTAL_ENABLED=true", "chen.wiki.public-portal-enabled=false")
                .run(context -> assertFalse(context.getBean(WikiController.class).portalStatus().getData()));
    }

    @Test
    void closedPortalDeniesListSearchAndDetailsWithoutReadingPublication() throws Exception {
        WikiPublicationService publication = mock(WikiPublicationService.class);
        ObjectProvider<WikiPublicationService> provider = provider(publication);
        MockMvc mvc = mvc(provider, false);

        mvc.perform(get("/wiki/pages").param("portalStatus", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/wiki/pages")).andExpect(status().isNotFound());
        mvc.perform(get("/wiki/pages").param("q", "Redis")).andExpect(status().isNotFound());
        mvc.perform(get("/wiki/pages/1")).andExpect(status().isNotFound());
        verifyNoInteractions(publication);
        verifyNoInteractions(provider);
    }

    @Test
    void enabledPortalUsesExistingPublishedAuthorityAndMissingServiceFailsClosed() throws Exception {
        WikiPublicationService publication = mock(WikiPublicationService.class);
        when(publication.list(anyString(), anyString(), eq(1), eq(12)))
                .thenReturn(new Page<>(List.of(), 0, 1, 12, List.of()));
        MockMvc open = mvc(provider(publication), true);
        open.perform(get("/wiki/pages").param("portalStatus", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        open.perform(get("/wiki/pages").param("q", "Redis")).andExpect(status().isOk());
        open.perform(get("/wiki/pages/1")).andExpect(status().isOk());
        verify(publication).list("Redis", "", 1, 12);
        verify(publication).read(1);

        MockMvc unavailable = mvc(provider(null), true);
        unavailable.perform(get("/wiki/pages").param("portalStatus", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        unavailable.perform(get("/wiki/pages")).andExpect(status().isNotFound());
        unavailable.perform(get("/wiki/pages/1")).andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WikiPublicationService> provider(WikiPublicationService service) {
        ObjectProvider<WikiPublicationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        clearInvocations(provider);
        return provider;
    }

    private static MockMvc mvc(ObjectProvider<WikiPublicationService> provider, boolean enabled) {
        return MockMvcBuilders.standaloneSetup(new WikiController(provider, enabled))
                .setControllerAdvice(new WikiExceptionHandler()).build();
    }
}
