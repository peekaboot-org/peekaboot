package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

/** Absent collections bind as empty (see {@link ActuatorResponseParser}). */
public record ConfigPropsResponse(Map<String, ConfigContext> contexts) {

    public ConfigPropsResponse {
        contexts = Absent.orEmpty(contexts);
    }

    public record ConfigContext(Map<String, ConfigBean> beans, String parentId) {
        public ConfigContext {
            beans = Absent.orEmpty(beans);
        }
    }

    public record ConfigBean(String prefix, Map<String, Object> properties) {
        public ConfigBean {
            properties = Absent.orEmpty(properties);
        }
    }
}
