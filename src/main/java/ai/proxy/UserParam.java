package ai.proxy;


import java.lang.annotation.*;

/**
 * @author Josh Long
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface UserParam {

	String value() default "";
}