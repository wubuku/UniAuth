package org.dddml.uniauth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SPA fallback must forward client routes to index.html without ever
 * swallowing backend prefixes such as /api or the H2 console.
 */
class SpaControllerTest {

    @Test
    void reactFrontendForwardsKnownRoutesToIndexHtml() {
        SpaController controller = reactController();

        assertThat(controller.loginPage()).isEqualTo("forward:/index.html");
        assertThat(controller.testPage()).isEqualTo("forward:/index.html");
        assertThat(controller.oauth2Callback()).isEqualTo("forward:/index.html");
        assertThat(controller.spaRoutes("dashboard")).isEqualTo("forward:/index.html");
    }

    @Test
    void nonReactFrontendRedirectsToRoot() {
        SpaController controller = new SpaController();
        ReflectionTestUtils.setField(controller, "frontendType", "legacy");

        assertThat(controller.loginPage()).isEqualTo("redirect:/");
        assertThat(controller.testPage()).isEqualTo("redirect:/");
        assertThat(controller.oauth2Callback()).isEqualTo("redirect:/");
        assertThat(controller.spaRoutes("dashboard")).isEqualTo("redirect:/");
    }

    @Test
    void spaRoutesLeavesBackendPrefixesUnhandled() {
        SpaController controller = reactController();

        assertThat(controller.spaRoutes("api/user")).isNull();
        assertThat(controller.spaRoutes("h2-console/login")).isNull();
        assertThat(controller.spaRoutes("favicon.ico")).isNull();
        // A plain segment that merely starts with the word api is not excluded.
        assertThat(controller.spaRoutes("apiary")).isEqualTo("forward:/index.html");
    }

    private SpaController reactController() {
        SpaController controller = new SpaController();
        ReflectionTestUtils.setField(controller, "frontendType", "react");
        return controller;
    }
}
