package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.controller.TracingController;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

@AutoConfiguration(after = PeekabootAutoConfiguration.class)
@ConditionalOnClass(TraceQueryService.class)
@ConditionalOnBean(TraceQueryService.class)
@Import(TracingController.class)
public class TracingControllerAutoConfiguration {
}
