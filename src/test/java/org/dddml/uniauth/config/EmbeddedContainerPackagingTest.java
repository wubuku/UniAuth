package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedContainerPackagingTest {

    @Test
    void tomcatExamplesWebApplicationIsNotOnTheRuntimeClasspath() {
        assertThat(getClass().getClassLoader().getResource(
                "webapps/examples/websocket/chat/index.xhtml"
        )).isNull();
        assertThat(getClass().getClassLoader().getResource(
                "examples/websocket/chat/index.xhtml"
        )).isNull();
    }
}
