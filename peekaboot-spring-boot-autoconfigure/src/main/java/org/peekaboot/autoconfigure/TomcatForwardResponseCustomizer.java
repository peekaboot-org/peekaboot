package org.peekaboot.autoconfigure;

import java.lang.reflect.Method;
import org.apache.catalina.Context;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.util.ReflectionUtils;

/**
 * Stops Tomcat 11 from suspending a wrapped response after a forward (Tomcat bug 68634,
 * {@code suspendWrappedResponseAfterForward}), which would drop the toolbar's late write and
 * leave a {@code forward:} view as an empty 200. Tomcat without the setter (before 10.1.20)
 * closes the response instead and is left alone.
 */
public class TomcatForwardResponseCustomizer implements TomcatContextCustomizer {

    private static final Method SET_SUSPEND_WRAPPED_RESPONSE_AFTER_FORWARD =
            ReflectionUtils.findMethod(Context.class, "setSuspendWrappedResponseAfterForward", boolean.class);

    @Override
    public void customize(Context context) {
        if (SET_SUSPEND_WRAPPED_RESPONSE_AFTER_FORWARD == null) {
            return;
        }
        ReflectionUtils.invokeMethod(SET_SUSPEND_WRAPPED_RESPONSE_AFTER_FORWARD, context, false);
    }
}
