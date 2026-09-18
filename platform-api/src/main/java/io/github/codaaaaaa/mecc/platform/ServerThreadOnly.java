package io.github.codaaaaaa.mecc.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks platform methods that touch Minecraft/AE2 state and therefore may only be called on the
 * Minecraft server thread. Other threads must go through
 * {@link io.github.codaaaaaa.mecc.platform.thread.ServerThreadGateway}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ServerThreadOnly {
}
