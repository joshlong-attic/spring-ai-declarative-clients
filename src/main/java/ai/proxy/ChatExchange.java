package ai.proxy;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
//@Reflective(HttpExchangeReflectiveProcessor.class)
public @interface ChatExchange {
    String user() default "";
    String system() default "";
}
